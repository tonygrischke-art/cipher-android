package com.aetheria.vance.brain

import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * On-device inference engine with NPU acceleration via MediaTek Neuron Adapter.
 *
 * Two model slots:
 *   - ACTION    → mobile_actions_q8_ekv1024.litertlm  (functiongemma-270m, device actions)
 *   - REASONING → gemma-3n-E2B-it-int4.litertlm      (gemma-3n, general reasoning)
 *
 * NPU Integration:
 *   - Attempts NPU execution first via libneuron_adapter_mgvi.so
 *   - Falls back to CPU delegate if NPU compilation fails
 *   - Falls back to Groq if local models are unavailable
 *
 * CRASH FIX: NpuBridge is injected by Hilt. If null or !isAvailable, skips NPU → Groq fallback.
 */
class LiteRTEngine(
    private val context: android.content.Context,
    private val modelDir: String = "/data/local/tmp/cipher_models/",
    private val npuBridge: NpuBridge? = null
) {

    companion object {
        private const val TAG = "LiteRTEngine"
        private const val ACTION_MODEL = "mobile_actions_q8_ekv1024.litertlm"
        private const val REASONING_MODEL = "gemma-3n-E2B-it-int4.litertlm"
        private const val MAX_TOKENS = 1024
        private const val TOP_K = 40
        private const val TEMPERATURE = 0.7f
        private const val RANDOM_SEED = 101
        private const val INFERENCE_TIMEOUT_MS = 45_000L
        private const val NPU_COMPILE_TIMEOUT_MS = 30_000L
    }

    enum class ModelSlot(val fileName: String) {
        ACTION(ACTION_MODEL),
        REASONING(REASONING_MODEL)
    }

    enum class ComputeBackend { NPU, CPU, GPU, UNAVAILABLE }

    data class InferenceResult(
        val text: String,
        val backend: ComputeBackend,
        val inferenceTimeMs: Long,
        val tokensGenerated: Int = 0
    )

    private val sessions = mutableMapOf<ModelSlot, LlmInference>()

    // NpuBridge is injected by Hilt via AppModule. May be null if NPU is unavailable.
    private var npuAvailable = false
    private var npuInitAttempted = false

    init {
        // CRASH FIX: Do NOT initialize NPU in init block.
        // NPU init is deferred to first actual use (generate/tryNpuInference).
        // This prevents kernel panic on MT6878 when Hilt constructs the singleton
        // during app startup — the NPU driver may not be ready at that point.
        Log.i(TAG, "LiteRTEngine created. NPU will be initialized on first use (npuBridge=${if (npuBridge != null) "injected" else "null"})")
    }

    /**
     * Lazily initialize NPU on first use. Safe to call multiple times.
     * Returns true if NPU is ready for inference.
     */
    private fun ensureNpuInitialized(): Boolean {
        if (npuInitAttempted) return npuAvailable
        npuInitAttempted = true
        if (npuBridge == null || !NpuBridge.nativeLibAvailable) {
            Log.w(TAG, "NpuBridge not available")
            return false
        }
        try {
            // Safe: npuBridge.initialize() calls nativeInit() which dlopen's lazily
            npuAvailable = npuBridge.initialize() && npuBridge.initNpu()
            Log.i(TAG, "NPU lazy initialization result: $npuAvailable")
        } catch (e: Exception) {
            Log.e(TAG, "NPU initialization failed with exception — NPU unavailable", e)
            npuAvailable = false
        }
        return npuAvailable
    }

    private fun modelFile(slot: ModelSlot): File = File(modelDir, slot.fileName)

    fun isModelAvailable(slot: ModelSlot): Boolean = modelFile(slot).exists()

    fun getAvailableModels(): List<ModelSlot> =
        ModelSlot.entries.filter { isModelAvailable(it) }

    fun isNpuAvailable(): Boolean = ensureNpuInitialized()

    /**
     * Get current compute backend for a model slot.
     */
    fun getBackend(slot: ModelSlot): ComputeBackend {
        return when {
            !isModelAvailable(slot) -> ComputeBackend.UNAVAILABLE
            ensureNpuInitialized() -> ComputeBackend.NPU
            else -> ComputeBackend.CPU
        }
    }

    @Synchronized
    private fun getOrCreateSession(slot: ModelSlot): LlmInference {
        sessions[slot]?.let { return it }
        val file = modelFile(slot)
        if (!file.exists()) throw ModelNotFoundException(slot.name, file.absolutePath)

        val backend = if (npuAvailable) "NPU" else "CPU"
        Log.d(TAG, "Creating $backend session for ${slot.name}: ${file.length() / 1024 / 1024} MB")

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .setTopK(TOP_K)
            .setTemperature(TEMPERATURE)
            .setRandomSeed(RANDOM_SEED)
            .build()

        val session = LlmInference.createFromOptions(context, options)
        sessions[slot] = session
        Log.d(TAG, "Session ready for ${slot.name} on $backend")
        return session
    }

    /**
     * Blocking full-response inference with NPU priority.
     * Attempts NPU first, falls back to CPU if compilation fails.
     */
    suspend fun generate(prompt: String, slot: ModelSlot): InferenceResult =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()

            // Try NPU first if available (and bridge exists)
            if (ensureNpuInitialized() && npuBridge != null) {
                val npuResult = tryNpuInference(prompt, slot)
                if (npuResult != null) {
                    return@withContext npuResult
                }
                Log.w(TAG, "NPU inference failed, falling back to CPU")
            }

            // CPU fallback via MediaPipe LiteRT
            val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                try {
                    val session = getOrCreateSession(slot)
                    val text = session.generateResponse(prompt)
                    val elapsed = System.currentTimeMillis() - startTime
                    InferenceResult(
                        text = text,
                        backend = ComputeBackend.CPU,
                        inferenceTimeMs = elapsed,
                        tokensGenerated = estimateTokens(text)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "generate failed for ${slot.name}", e)
                    throw e
                }
            }

            if (result == null) {
                throw InferenceTimeoutException(slot.name, INFERENCE_TIMEOUT_MS)
            }
            result
        }

    /**
     * Attempt NPU-accelerated inference via Neuron Adapter.
     */
    private fun tryNpuInference(prompt: String, slot: ModelSlot): InferenceResult? {
        if (npuBridge == null) return null
        val file = modelFile(slot)
        if (!file.exists()) return null

        return try {
            val startTime = System.currentTimeMillis()
            val result = npuBridge.runInference(file.absolutePath, prompt)

            // Check if NPU returned an error
            if (result.startsWith("NPU_")) {
                Log.w(TAG, "NPU error: $result")
                return null
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "NPU inference completed in ${elapsed}ms")

            InferenceResult(
                text = result,
                backend = ComputeBackend.NPU,
                inferenceTimeMs = elapsed,
                tokensGenerated = estimateTokens(result)
            )
        } catch (e: Exception) {
            Log.e(TAG, "NPU inference exception", e)
            null
        }
    }

    /**
     * Streaming inference — yields the full response as a single emission.
     * NPU does not support true token streaming; this buffers and emits.
     */
    fun generateStreaming(prompt: String, slot: ModelSlot): Flow<InferenceResult> = flow {
        try {
            emit(generate(prompt, slot))
        } catch (e: ModelNotFoundException) {
            Log.e(TAG, "Model not found: ${e.slot}")
            emit(InferenceResult(
                text = "[model not found: ${e.slot}]",
                backend = ComputeBackend.UNAVAILABLE,
                inferenceTimeMs = 0
            ))
        } catch (e: InferenceTimeoutException) {
            Log.e(TAG, "Inference timeout: ${e.slot}")
            emit(InferenceResult(
                text = "[inference timed out]",
                backend = ComputeBackend.UNAVAILABLE,
                inferenceTimeMs = e.timeoutMs
            ))
        }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    fun release() {
        Log.d(TAG, "Releasing all LiteRT sessions")
        for ((slot, session) in sessions) {
            try { session.close() } catch (e: Exception) { Log.w(TAG, "Error closing ${slot.name}", e) }
        }
        sessions.clear()
        npuBridge?.shutdownNpu()
    }

    private fun estimateTokens(text: String): Int {
        // Rough estimate: ~4 characters per token for English
        return text.length / 4
    }

    data class ModelNotFoundException(val slot: String, val path: String) :
        Exception("Model not found for slot '$slot' at path: $path")

    data class InferenceTimeoutException(val slot: String, val timeoutMs: Long) :
        Exception("Inference timed out for slot '$slot' after ${timeoutMs}ms")
}
