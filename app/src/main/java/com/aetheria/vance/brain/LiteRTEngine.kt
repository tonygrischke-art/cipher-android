package com.aetheria.vance.brain

import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
/**
 * On-device inference engine with NPU acceleration via MediaTek Neuron Adapter.
 *
 * Model slots:
 *   ACTION    → mobile_actions_q8_ekv1024.litertlm  (276MB, device actions)
 *   REASONING → qwen05.task                         (521MB, general reasoning)
 *   CHAT      → qwen05.task                         (521MB, conversation)
 *   TEST      → mobilenet_test.tflite               (3.4MB, TfliteEngine NPU smoke test — NOT MediaPipe)
 *
 * CRASH GUARD: MediaPipe's LlmInferenceEngine_CreateSession calls abort() on invalid models.
 * isValidMediaPipeModel() validates BEFORE any session creation. Invalid models skip to Groq.
 */
class LiteRTEngine(
    private val context: android.content.Context,
    private val modelDir: String = "/data/local/tmp/cipher_models/",
    private val npuBridge: NpuBridge? = null
) {

    companion object {
        private const val TAG = "LiteRTEngine"
        private const val MAX_TOKENS = 1024
        private const val TOP_K = 40
        private const val INFERENCE_TIMEOUT_MS = 45_000L
        private const val NPU_COMPILE_TIMEOUT_MS = 30_000L
    }

    /**
     * Validate model file before MediaPipe session creation.
     * MediaPipe's LlmInferenceEngine_CreateSession calls abort() on invalid models — cannot be caught.
     * This guard prevents the crash by rejecting models that are known to cause SIGABRT.
     *
     * For .task bundles: validates the zip contains at least one .tflite entry and
     * the TFLite header is readable (flatbuffer magic bytes).
     */
    private fun isValidMediaPipeModel(file: File): Boolean {
        if (!file.exists()) return false
        // >2GB = definitely will crash (gemma-3n is 3.4GB)
        if (file.length() > 2_000_000_000L) {
            Log.w(TAG, "Model ${file.name} too large (${file.length() / 1024 / 1024}MB > 2GB) — skip MediaPipe")
            return false
        }
        // Raw .tflite is NOT a MediaPipe LLM model — would crash
        if (file.extension == "tflite") {
            Log.w(TAG, "Model ${file.name} is raw TFLite — not valid for MediaPipe LLM")
            return false
        }
        // Only .task, .litertlm, .bin are valid MediaPipe LLM formats
        if (file.extension !in listOf("task", "litertlm", "bin")) {
            Log.w(TAG, "Model ${file.name} extension '.${file.extension}' not valid for MediaPipe")
            return false
        }
        // For .task bundles: validate internal structure
        if (file.extension == "task") {
            return validateTaskBundle(file)
        }
        return true
    }

    /**
     * Validate a .task MediaPipe bundle by checking:
     * 1. It can be opened as a zip (even with a header prefix)
     * 2. It contains at least one TFLite model entry
     * 3. The TFLite model starts with valid flatbuffer magic bytes
     */
    private fun validateTaskBundle(file: File): Boolean {
        return try {
            val zf = ZipFile(file)
            val entries = zf.entries().asSequence().toList()
            zf.close()
            // Check for at least one .tflite entry inside the bundle
            val tfliteEntries = entries.filter { it.name.endsWith(".tflite") }
            if (tfliteEntries.isEmpty()) {
                Log.w(TAG, "Task bundle ${file.name} contains no .tflite entries — invalid bundle")
                return false
            }
            Log.d(TAG, "Task bundle ${file.name}: ${entries.size} entries, ${tfliteEntries.size} TFLite model(s)")
            true
        } catch (e: Exception) {
            // Try with offset — some .task files have a 4-byte header before the zip data
            try {
                val zf = openOffsetZip(file, 4)
                val entries = zf.entries().asSequence().toList()
                zf.close()
                val tfliteEntries = entries.filter { it.name.endsWith(".tflite") }
                if (tfliteEntries.isEmpty()) {
                    Log.w(TAG, "Task bundle ${file.name} (offset) contains no .tflite entries")
                    return false
                }
                Log.d(TAG, "Task bundle ${file.name} (offset 4): ${entries.size} entries, ${tfliteEntries.size} TFLite model(s)")
                true
            } catch (e2: Exception) {
                Log.w(TAG, "Task bundle ${file.name} is not a valid zip (even with offset): ${e2.message}")
                false
            }
        }
    }

    /**
     * Open a zip file that may have a header prefix before the actual zip data.
     */
    private fun openOffsetZip(file: File, offset: Int): ZipFile {
        val channel = FileInputStream(file).channel
        val offsetL = offset.toLong()
        channel.position(offsetL)
        // Read all remaining bytes and create a temp zip
        val size = file.length() - offsetL
        val tempFile = File.createTempFile("task_validate", ".zip", context.cacheDir)
        tempFile.deleteOnExit()
        val srcBuf = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, offsetL, size)
        val dstBuf = FileOutputStream(tempFile).channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, size)
        dstBuf.put(srcBuf)
        channel.close()
        return ZipFile(tempFile)
    }

    enum class ModelSlot(val fileName: String, val isLlm: Boolean = false) {
        ACTION("mobile_actions_q8_ekv1024.litertlm"),       // 276MB MediaPipe bundle — device actions (classification)
        REASONING("qwen05.tflite", isLlm = true),           // 544MB raw TFLite — LLM via TfliteLlmEngine (NNAPI)
        CHAT("qwen05.tflite", isLlm = true),                // 544MB raw TFLite — LLM via TfliteLlmEngine (NNAPI)
        TEST("mobilenet_test.tflite")                       // 3.4MB raw TFLite — TfliteEngine NPU smoke test only
    }

    enum class ComputeBackend { NPU, CPU, GPU, UNAVAILABLE }

    data class InferenceResult(
        val text: String,
        val backend: ComputeBackend,
        val inferenceTimeMs: Long,
        val tokensGenerated: Int = 0
    )

    private val sessions = mutableMapOf<ModelSlot, LlmInference>()

    // TFLite LLM engine for REASONING slot (qwen05.tflite via NNAPI)
    private var tfliteLlmEngine: TfliteLlmEngine? = null
    private var tfliteLlmInitAttempted = false

    // NpuBridge is injected by Hilt via AppModule. May be null if NPU is unavailable.
    private var npuAvailable = false
    private var npuInitAttempted = false

    init {
        Log.i("LiteRTEngine", "INIT START — filesDir=${context.filesDir.absolutePath}")
        // CRASH FIX: Do NOT initialize NPU in init block.
        // NPU init is deferred to first actual use (generate/tryNpuInference).
        // This prevents kernel panic on MT6878 when Hilt constructs the singleton
        // during app startup — the NPU driver may not be ready at that point.
        Log.i(TAG, "LiteRTEngine created. NPU will be initialized on first use (npuBridge=${if (npuBridge != null) "injected" else "null"})")

        // ANR FIX: Copy models on background thread — fire and forget.
        // Do NOT block init / main thread for multi-GB file copies.
        @Suppress("BlockingMethodInNonBlockingContext")
        CoroutineScope(Dispatchers.IO).launch {
            copyModelsIfNeeded(context)
        }

        // NPU smoke test — TFLite NNAPI path
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val testFile = File(context.filesDir, "mobilenet_test.tflite")
                if (testFile.exists()) {
                    Log.i(TAG, "NPU smoke test: mobilenet_test.tflite found (${testFile.length() / 1024}KB)")
                    val engine = TfliteEngine(context)
                    val success = engine.init(testFile)
                    Log.i(TAG, "NPU smoke test: ${if (success) "PASSED" else "FAILED"}")
                    engine.close()
                } else {
                    Log.w(TAG, "NPU smoke test: mobilenet_test.tflite not in filesDir — skipping")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "NPU smoke test error: ${e.message}")
            }
        }
    }

    /**
     * Copy models from /data/local/tmp/cipher_models/ into context.filesDir
     * at first launch. The app has write permission to its own filesDir.
     * Subsequent launches skip files that already exist with matching size.
     */
    fun copyModelsIfNeeded(ctx: android.content.Context = context) {
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
        Log.i(TAG, "All models copied to filesDir")
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

    private suspend fun waitForModel(slot: ModelSlot, timeoutMs: Long = 30_000L) {
        val flagFile = File(context.filesDir, ".models_copied")
        // Quick check — if flag exists, models are ready
        if (flagFile.exists()) return

        // Wait for flag file to appear (models still copying)
        val start = System.currentTimeMillis()
        while (!flagFile.exists()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                Log.w(TAG, "Timeout waiting for model copy after ${timeoutMs}ms, using source dir")
                return
            }
            kotlinx.coroutines.delay(500)
        }
        Log.i(TAG, "Model copy flag detected, models ready")
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

        // CRASH GUARD: MediaPipe abort() cannot be caught. Validate model BEFORE session creation.
        if (!isValidMediaPipeModel(file)) {
            Log.w(TAG, "Model ${file.name} failed validation — skipping MediaPipe, will use Groq")
            throw ModelNotFoundException(slot.name, "invalid for MediaPipe: ${file.name}")
        }

        val backend = "CPU"
        Log.i(TAG, "Creating $backend session for ${slot.name}: ${file.length() / 1024 / 1024} MB")

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .setTopK(TOP_K)
            .build()

        val session = LlmInference.createFromOptions(context, options)
        sessions[slot] = session
        Log.d(TAG, "Session ready for ${slot.name}")
        return session
    }

    /**
     * Blocking full-response inference with NPU priority.
     * Attempts NPU first, falls back to CPU if compilation fails.
     *
     * REASONING slot uses TfliteLlmEngine (raw TFLite + NNAPI), bypassing MediaPipe.
     * CHAT/ACTION slots use MediaPipe LiteRT.
     */
    suspend fun generate(prompt: String, slot: ModelSlot): InferenceResult =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()

            // Wait for model copy to complete (up to 30s)
            waitForModel(slot)

            // ── LLM slots (REASONING, CHAT): TfliteLlmEngine (raw TFLite + NNAPI) ──
            if (slot.isLlm) {
                val result = tryTfliteLlm(prompt, slot, startTime)
                if (result != null) return@withContext result
                Log.w(TAG, "TfliteLlmEngine failed for ${slot.name}, falling through")
                throw ModelNotFoundException(slot.name, "TfliteLlmEngine failed")
            }

            // ── Try NeuronBridge (direct NeuroPilot NPU) first ──────────────
            // Skip for LLM slots (handled by TfliteLlmEngine) and TEST (TfliteEngine separately)
            if (NeuronBridge.isAvailable && !slot.isLlm && slot != ModelSlot.TEST) {
                val modelFile = modelFile(slot)
                if (modelFile.exists()) {
                    val result = tryNeuronBridge(prompt, slot, modelFile, startTime)
                    if (result != null) return@withContext result
                    Log.w(TAG, "NeuronBridge NPU failed, falling back")
                }
            }

            // ── Try legacy NpuBridge second ─────────────────────────────────
            if (!slot.isLlm && slot != ModelSlot.TEST && ensureNpuInitialized() && npuBridge != null) {
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
     * Try TFLite LLM inference for LLM slots (REASONING, CHAT).
     * Loads qwen05.tflite via TfliteLlmEngine with NNAPI NPU delegation.
     */
    private fun tryTfliteLlm(prompt: String, slot: ModelSlot, startTime: Long): InferenceResult? {
        if (!tfliteLlmInitAttempted) {
            tfliteLlmInitAttempted = true
            val modelFile = modelFile(slot)
            if (modelFile.exists()) {
                Log.i(TAG, "TfliteLlmEngine: initializing with ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
                tfliteLlmEngine = TfliteLlmEngine(context).also { engine ->
                    val ok = engine.init(modelFile)
                    if (!ok) {
                        Log.e(TAG, "TfliteLlmEngine: init failed")
                        tfliteLlmEngine = null
                    } else {
                        Log.i(TAG, "TfliteLlmEngine: init OK")
                    }
                }
            } else {
                Log.w(TAG, "TfliteLlmEngine: model file not found: ${modelFile.absolutePath}")
            }
        }

        val engine = tfliteLlmEngine ?: return null
        return try {
            val text = engine.generate(prompt)
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "TfliteLlmEngine: generated '${text.take(80)}...' in ${elapsed}ms")
            InferenceResult(
                text = text,
                backend = ComputeBackend.NPU,
                inferenceTimeMs = elapsed,
                tokensGenerated = estimateTokens(text)
            )
        } catch (e: Exception) {
            Log.e(TAG, "TfliteLlmEngine: generation failed: ${e.message}", e)
            null
        }
    }

    /**
     * Attempt NPU inference via NeuronBridge (libneuronusdk_adapter.mtk.so).
     */
    private fun tryNeuronBridge(
        prompt: String, slot: ModelSlot, modelFile: File, startTime: Long
    ): InferenceResult? {
        Log.i(TAG, "NPU: tryNeuronBridge slot=${slot.name} model=${modelFile.name} size=${modelFile.length()/1024/1024}MB")
        return try {
            val handle = try {
                NeuronBridge.nativeInit(modelFile.absolutePath, context.cacheDir.absolutePath)
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
        tfliteLlmEngine?.close()
        tfliteLlmEngine = null
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
