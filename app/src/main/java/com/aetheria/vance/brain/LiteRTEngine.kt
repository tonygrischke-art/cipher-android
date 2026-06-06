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
        ACTION("mobile_actions_q8_ekv1024.litertlm"),
        REASONING("gemma-3n-E2B-it-int4.litertlm"),
        CODING("vibethinker-1.5b.litertlm"),      // abliterated coding model
        VISION("gemma-4-E2B-vision.litertlm"),    // image/video understanding
        CHAT("qwen05.task"),                        // Qwen 0.5B chat via MediaPipe Task
        TEST("hermes_int8.tflite")                   // NPU smoke test (80MB int8 model)
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
        copyModelsIfNeeded(context)
    }

    /**
     * Copy models from /data/local/tmp/cipher_models/ into context.filesDir
     * at first launch. The app has write permission to its own filesDir.
     * Subsequent launches skip files that already exist with matching size.
     */
    private fun copyModelsIfNeeded(ctx: android.content.Context) {
        val srcDir = File(modelDir)
        val dstDir = ctx.filesDir
        if (!srcDir.exists()) { Log.w(TAG, "Model source dir missing: $modelDir"); return }
        val files = srcDir.listFiles() ?: return
        if (files.isEmpty()) { Log.w(TAG, "No model files in $modelDir"); return }
        for (src in files) {
            val dst = File(dstDir, src.name)
            if (!dst.exists() || dst.length() != src.length()) {
                Log.i(TAG, "Copying model: ${src.name} (${src.length() / 1024 / 1024}MB)")
                try {
                    src.copyTo(dst, overwrite = true)
                    Log.i(TAG, "Copy complete: ${src.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy ${src.name}", e)
                }
            }
        }
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

    private fun modelFile(slot: ModelSlot): File {
        val filesDirPath = File(context.filesDir, slot.fileName).absolutePath
        if (File(filesDirPath).exists()) return File(filesDirPath)
        return File(modelDir, slot.fileName)
    }

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

        val backend = if (ensureNpuInitialized()) "NPU" else "CPU"
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

            // ── Try NeuronBridge (direct NeuroPilot NPU) first ──────────────
            // Skip NPU for CHAT (.task files are MediaPipe bundles, not raw NPU binaries)
            if (NeuronBridge.isAvailable && slot != ModelSlot.CHAT) {
                val modelFile = modelFile(slot)
                if (modelFile.exists()) {
                    val result = tryNeuronBridge(prompt, slot, modelFile, startTime)
                    if (result != null) return@withContext result
                    Log.w(TAG, "NeuronBridge NPU failed, falling back")
                }
            }

            // ── Try legacy NpuBridge second ─────────────────────────────────
            if (slot != ModelSlot.CHAT && ensureNpuInitialized() && npuBridge != null) {
                val npuResult = tryNpuInference(prompt, slot)
                if (npuResult != null) {
                    return@withContext npuResult
                }
                Log.w(TAG, "Legacy NPU inference failed, falling back to CPU via MediaPipe")
            }

            // ── CPU fallback via MediaPipe LiteRT ───────────────────────────
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
     * Attempt NPU inference via NeuronBridge (libneuronusdk_adapter.mtk.so).
     */
    private fun tryNeuronBridge(
        prompt: String, slot: ModelSlot, modelFile: File, startTime: Long
    ): InferenceResult? {
        return try {
            // For TEST slot, use hermes_int8.tflite from filesDir (80MB valid TFLite)
            val npuModelFile = if (slot == ModelSlot.TEST) {
                val f = File(context.filesDir, "hermes_int8.tflite")
                if (f.exists()) {
                    Log.i(TAG, "NPU TEST: using hermes_int8.tflite from filesDir (${f.length()/1024/1024}MB)")
                    f
                } else {
                    Log.w(TAG, "NPU TEST: hermes_int8.tflite not found in filesDir, falling back to ${modelFile.name}")
                    modelFile
                }
            } else modelFile

            Log.i(TAG, "NPU: nativeInit slot=${slot.name} model=${npuModelFile.name} size=${npuModelFile.length()/1024/1024}MB path=${npuModelFile.absolutePath}")
            val handle = try {
                NeuronBridge.nativeInit(npuModelFile.absolutePath, context.cacheDir.absolutePath)
            } catch (e: Throwable) {
                Log.e(TAG, "NPU: nativeInit threw for ${slot.name}: ${e.message}")
                0L
            }
            Log.i(TAG, "NPU: nativeInit returned handle=$handle for ${slot.name}")
            if (handle == 0L) {
                Log.w(TAG, "NPU: nativeInit returned 0 for ${slot.name} — NPU unavailable or model rejected")
                return null
            }
            try {
                Log.i(TAG, "NPU: calling nativeInfer for ${slot.name} prompt='${prompt.take(50)}'")
                val text = NeuronBridge.nativeInfer(handle, prompt)
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "NPU: nativeInfer result for ${slot.name}: '${text.take(100)}' (${elapsed}ms)")

                if (text.startsWith("NPU_ERROR:")) {
                    Log.w(TAG, "NPU: inference error for ${slot.name}: $text")
                    return null
                }

                Log.i(TAG, "NPU: inference completed for ${slot.name} in ${elapsed}ms")
                InferenceResult(
                    text = text,
                    backend = ComputeBackend.NPU,
                    inferenceTimeMs = elapsed,
                    tokensGenerated = estimateTokens(text)
                )
            } finally {
                if (handle != 0L) {
                    NeuronBridge.nativeClose(handle)
                    Log.i(TAG, "NPU: nativeClose done for ${slot.name}")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "NPU: inference exception for ${slot.name}: ${e.message}")
            null
        }
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
