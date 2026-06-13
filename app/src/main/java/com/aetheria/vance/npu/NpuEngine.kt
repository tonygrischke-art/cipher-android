package com.aetheria.vance.npu

import android.content.Context
import android.util.Log
import com.aetheria.vance.brain.NeuronBridge
import java.io.File

class NpuEngine(private val context: Context) {

    private val TAG = "CipherNpuEngine"
    private var initialized = false

    companion object {
        private const val MODEL_PATH =
            "/data/local/tmp/cipher_models/qwen15_int8.tflite"
    }

    val isInitialised: Boolean
        get() = initialized

    fun setupInferenceEngine(): Boolean {
        val modelFile = File(MODEL_PATH)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model missing at $MODEL_PATH")
            return false
        }

        val bridgeReady = NeuronBridge.initAdapter()
        if (!bridgeReady) {
            Log.w(TAG, "NeuronBridge unavailable — continuing with TFLite CPU")
        }

        val engineReady = VanceNpuJni.initializeModel(MODEL_PATH)
        if (!engineReady) {
            Log.e(TAG, "VanceNpuJni init failed")
            return false
        }

        initialized = true
        Log.i(TAG, "NpuEngine ready — NPU bridge: $bridgeReady")
        return true
    }

    // Legacy compatibility
    fun init(modelPath: String): Boolean {
        return setupInferenceEngine()
    }

    fun generate(prompt: String): String? {
        if (!initialized) {
            Log.e(TAG, "generate called before init")
            return null
        }
        return try {
            VanceNpuJni.runInference(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            null
        }
    }

    fun generateResponse(prompt: String): String? = generate(prompt)

    fun teardown() {
        VanceNpuJni.terminateEngine()
        initialized = false
    }

    fun close() {
        teardown()
    }
}
