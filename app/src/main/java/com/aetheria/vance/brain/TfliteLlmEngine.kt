package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LiteRT LLM engine — loads .litertlm / .task model files via MediaPipe LlmInference.
 * Loads directly from /data/local/tmp/cipher_models/ — no intermediate copy needed.
 */
class TfliteLlmEngine(private val context: Context) {

    companion object {
        private const val TAG = "TfliteLlmEngine"

        // Load directly from /data/local/tmp — skip filesDir copy entirely.
        const val MODEL_BASE_PATH = "/data/local/tmp/cipher_models"

        private const val MAX_TOKENS        = 1024
        private const val TOPK              = 40
        private const val TEMPERATURE       = 0.8f
        private const val RANDOM_SEED       = 42
    }

    private var llmEngine: LlmInference? = null
    internal var isReady = false

    suspend fun initialize(modelPath: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val modelFile = java.io.File(modelPath)
                if (!modelFile.exists()) {
                    Log.e(TAG, "Model file not found: $modelPath")
                    return@withContext false
                }

                // Log file header for diagnostics
                val header = ByteArray(16)
                java.io.FileInputStream(modelFile).use { it.read(header) }
                val hex = header.joinToString(" ") { "%02X".format(it) }
                val ascii = String(header, Charsets.US_ASCII).filter { it.isLetterOrDigit() || it == '_' }
                Log.i(TAG, "File header [$ascii]: $hex")
                Log.i(TAG, "Loading model: $modelPath (${modelFile.length() / 1_048_576} MB)")

                val options = LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(MAX_TOKENS)
                    .setTopK(TOPK)
                    .setTemperature(TEMPERATURE)
                    .setRandomSeed(RANDOM_SEED)
                    .build()

                Log.i(TAG, "Calling LlmInference.createFromOptions() with modelPath=$modelPath")
                val engine = try {
                    LlmInference.createFromOptions(context, options)
                } catch (e: Throwable) {
                    Log.e(TAG, "createFromOptions THREW: ${e.javaClass.name}: ${e.message}", e)
                    return@withContext false
                }

                if (engine == null) {
                    Log.e(TAG, "createFromOptions returned null — model load failed silently")
                    return@withContext false
                }

                llmEngine = engine
                isReady = true
                Log.i(TAG, "LiteRT LLM engine ready ✓ modelPath=$modelPath")
                true

            } catch (e: Throwable) {
                Log.e(TAG, "Engine init failed with Throwable: ${e.javaClass.name}: ${e.message}", e)
                isReady = false
                false
            }
        }

    suspend fun generate(prompt: String): String =
        withContext(Dispatchers.IO) {
            check(isReady && llmEngine != null) { "TfliteLlmEngine not initialised" }
            try {
                llmEngine!!.generateResponse(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "generate() failed: ${e.message}", e)
                throw e
            }
        }

    fun release() {
        llmEngine?.close()
        llmEngine = null
        isReady = false
        Log.d(TAG, "Engine released")
    }
}
