package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LiteRT LLM engine for .litertlm model files.
 * Uses MediaPipe LlmInference API — NOT tflite::Interpreter.
 * .litertlm is NOT a TFLite flatbuffer; loading it via Interpreter
 * will always throw "ByteBuffer is not a valid TensorFlow Lite model flatbuffer".
 */
class TfliteLlmEngine(private val context: Context) {

    companion object {
        private const val TAG = "TfliteLlmEngine"

        // Load directly from /data/local/tmp — skip filesDir copy entirely.
        // copyModelsIfNeeded() is NOT needed for this path.
        private const val MODEL_BASE_PATH = "/data/local/tmp/cipher_models"
        private const val LLM_MODEL     = "$MODEL_BASE_PATH/gemma-3n-E2B-it-int4.litertlm"
        private const val ACTION_MODEL  = "$MODEL_BASE_PATH/mobile_actions_q8_ekv1024.litertlm"

        // Tuning knobs — adjust to taste / available RAM
        private const val MAX_TOKENS        = 1024
        private const val TOPK              = 40
        private const val TEMPERATURE       = 0.8f
        private const val RANDOM_SEED       = 42
    }

    // ── State ──────────────────────────────────────────────────────────────
    private var llmEngine: LlmInference? = null
    private var isReady = false

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Call once (e.g. from a background coroutine in your Service/ViewModel).
     * Returns true if the engine loaded successfully.
     */
    suspend fun initialize(useActionModel: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val modelPath = if (useActionModel) ACTION_MODEL else LLM_MODEL

                // Sanity-check the file exists and log its header
                val modelFile = java.io.File(modelPath)
                if (!modelFile.exists()) {
                    Log.e(TAG, "Model file not found: $modelPath")
                    return@withContext false
                }
                logFileHeader(modelFile)

                Log.d(TAG, "Loading LiteRT LLM model: $modelPath (${modelFile.length() / 1_048_576} MB)")

                val options = LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(MAX_TOKENS)
                    .setTopK(TOPK)
                    .setTemperature(TEMPERATURE)
                    .setRandomSeed(RANDOM_SEED)
                    .build()

                llmEngine = LlmInference.createFromOptions(context, options)
                isReady = true
                Log.d(TAG, "LiteRT LLM engine ready ✓")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Engine init failed: ${e.message}", e)
                isReady = false
                false
            }
        }

    // ── Inference ──────────────────────────────────────────────────────────

    /**
     * Blocking single-shot inference. Call from a coroutine (Dispatchers.IO).
     */
    suspend fun generate(prompt: String): String =
        withContext(Dispatchers.IO) {
            checkReady()
            try {
                llmEngine!!.generateResponse(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "generate() failed: ${e.message}", e)
                throw e
            }
        }

    /**
     * Streaming inference placeholder.
     * MediaPipe 0.10.14 generateResponseAsync(String) does not accept a callback.
     * Use generate() for blocking inference, or upgrade MediaPipe for streaming.
     */
    fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Fallback: run blocking generate and deliver result
        try {
            val result = llmEngine?.generateResponse(prompt)
            if (result != null) {
                onToken(result)
            }
            onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "generateStreaming() failed: ${e.message}", e)
            onError(e)
        }
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    fun release() {
        llmEngine?.close()
        llmEngine = null
        isReady = false
        Log.d(TAG, "Engine released")
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun checkReady() {
        check(isReady && llmEngine != null) {
            "TfliteLlmEngine is not initialised. Call initialize() first."
        }
    }

    /**
     * Logs the first 16 bytes of the model file for format verification.
     * Valid .litertlm starts with a LiteRT magic, NOT the TFLite 0x18000000 prefix.
     */
    private fun logFileHeader(file: java.io.File) {
        try {
            val header = ByteArray(16)
            java.io.FileInputStream(file).use { it.read(header) }
            val hex = header.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "File header [${file.name}]: $hex")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read file header: ${e.message}")
        }
    }
}
