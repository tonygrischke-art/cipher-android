package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

class NpuEngine(private val context: Context) {

    private var llmInference: LlmInference? = null
    private val TAG = "CipherNpuEngine"

    fun setupInferenceEngine(): Boolean {
        val modelFile = File("/data/local/tmp/cipher_models/cipher_qwen.task")
        if (!modelFile.exists()) {
            Log.e(TAG, "Abort: model missing at ${modelFile.absolutePath}")
            return false
        }

        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setTemperature(0.7f)
                .setTopK(40)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe LlmInference initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "LlmInference init failed: ${e.message}")
            false
        }
    }

    fun generateResponse(prompt: String): String? {
        return try {
            llmInference?.generateResponse(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            null
        }
    }

    fun teardown() {
        llmInference?.close()
        llmInference = null
    }
}
