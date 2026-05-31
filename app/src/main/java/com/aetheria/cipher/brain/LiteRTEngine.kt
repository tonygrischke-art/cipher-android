package com.aetheria.cipher.brain

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
 * On-device inference engine using MediaPipe LiteRT LLM Inference API.
 *
 * Two model slots:
 *   - ACTION    → mobile_actions_q8_ekv1024.litertlm  (functiongemma-270m, device actions)
 *   - REASONING → gemma-3n-E2B-it-int4.litertlm      (gemma-3n, general reasoning)
 */
class LiteRTEngine(
    private val context: android.content.Context,
    private val modelDir: String = "/data/local/tmp/cipher_models/"
) {

    companion object {
        private const val TAG = "LiteRTEngine"
        private const val ACTION_MODEL = "mobile_actions_q8_ekv1024.litertlm"
        private const val REASONING_MODEL = "gemma-3n-E2B-it-int4.litertlm"
        private const val MAX_TOKENS = 1024
        private const val TOP_K = 40
        private const val TEMPERATURE = 0.7f
        private const val RANDOM_SEED = 101
        private const val INFERENCE_TIMEOUT_MS = 15_000L
    }

    enum class ModelSlot(val fileName: String) {
        ACTION(ACTION_MODEL),
        REASONING(REASONING_MODEL)
    }

    private val sessions = mutableMapOf<ModelSlot, LlmInference>()

    private fun modelFile(slot: ModelSlot): File = File(modelDir, slot.fileName)

    fun isModelAvailable(slot: ModelSlot): Boolean = modelFile(slot).exists()

    fun getAvailableModels(): List<ModelSlot> =
        ModelSlot.entries.filter { isModelAvailable(it) }

    @Synchronized
    private fun getOrCreateSession(slot: ModelSlot): LlmInference {
        sessions[slot]?.let { return it }
        val file = modelFile(slot)
        if (!file.exists()) throw ModelNotFoundException(slot.name, file.absolutePath)
        Log.d(TAG, "Creating LlmInference for ${slot.name}: ${file.length() / 1024 / 1024} MB")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .setTopK(TOP_K)
            .setTemperature(TEMPERATURE)
            .setRandomSeed(RANDOM_SEED)
            .build()
        val session = LlmInference.createFromOptions(context, options)
        sessions[slot] = session
        Log.d(TAG, "Session ready for ${slot.name}")
        return session
    }

    /**
     * Blocking full-response inference.
     * Throws [InferenceTimeoutException] if model doesn't respond within timeout.
     * Throws [ModelNotFoundException] if model file is missing.
     */
    suspend fun generate(prompt: String, slot: ModelSlot): String =
        withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                try {
                    val session = getOrCreateSession(slot)
                    session.generateResponse(prompt)
                } catch (e: Exception) {
                    Log.e(TAG, "generate failed for ${slot.name}", e)
                    throw e
                }
            }
            if (result == null) throw InferenceTimeoutException(slot.name, INFERENCE_TIMEOUT_MS)
            result
        }

    /**
     * Streaming inference — yields the full response as a single emission.
     * Returns an error string if model is missing or times out.
     */
    fun generateStreaming(prompt: String, slot: ModelSlot): Flow<String> = flow {
        try {
            emit(generate(prompt, slot))
        } catch (e: ModelNotFoundException) {
            Log.e(TAG, "Model not found: ${e.slot}")
            emit("[model not found: ${e.slot}]")
        } catch (e: InferenceTimeoutException) {
            Log.e(TAG, "Inference timeout: ${e.slot}")
            emit("[inference timed out]")
        }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    fun release() {
        Log.d(TAG, "Releasing all LiteRT sessions")
        for ((slot, session) in sessions) {
            try { session.close() } catch (e: Exception) { Log.w(TAG, "Error closing ${slot.name}", e) }
        }
        sessions.clear()
    }

    data class ModelNotFoundException(val slot: String, val path: String) :
        Exception("Model not found for slot '$slot' at path: $path")

    data class InferenceTimeoutException(val slot: String, val timeoutMs: Long) :
        Exception("Inference timed out for slot '$slot' after ${timeoutMs}ms")
}
