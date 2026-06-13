package com.aetheria.vance.npu

import android.content.Context
import android.util.Log
import com.aetheria.vance.brain.NeuronBridge
import java.io.File

class NpuEngine(private val context: Context) {

    private val TAG = "CipherNpuEngine"
    @Volatile private var initialized = false

    companion object {
        private const val MODEL_PATH =
            "/data/local/tmp/cipher_models/qwen15_int8.tflite"
    }

    val isInitialised: Boolean
        get() = initialized

    fun setupInferenceEngine(): Boolean {
        // Guard: check RAM before loading model
        val runtime = Runtime.getRuntime()
        val availMB = runtime.freeMemory() / 1048576
        val totalMB = runtime.totalMemory() / 1048576
        Log.w("VanceMemory", "RAM before model load: ${availMB}MB free / ${totalMB}MB total")
        if (availMB < 200) {
            Log.e("VanceMemory", "CRITICAL: Low RAM (${availMB}MB free) — aborting NPU init to prevent OOM")
            return false
        }

        // Guard: model file must exist
        val modelFile = File(MODEL_PATH)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model missing at $MODEL_PATH")
            return false
        }

        // Load native library safely
        val bridgeLoaded = NeuronBridge.loadLibrary()
        if (!bridgeLoaded) {
            Log.w(TAG, "NeuronBridge library not available — NPU disabled")
        }

        // Init adapter (safe even if library didn't load — will return false)
        val bridgeReady = if (bridgeLoaded) {
            try {
                NeuronBridge.initAdapter()
            } catch (e: Exception) {
                Log.e(TAG, "NeuronBridge.initAdapter failed: ${e.message}")
                false
            }
        } else false

        if (!bridgeReady) {
            Log.w(TAG, "NeuronBridge unavailable — continuing with TFLite CPU")
        }

        // Initialize the TFLite engine via JNI
        val engineReady = try {
            VanceNpuJni.initializeModel(MODEL_PATH)
        } catch (e: Exception) {
            Log.e(TAG, "VanceNpuJni init failed: ${e.message}")
            false
        }

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
        try {
            VanceNpuJni.terminateEngine()
        } catch (e: Exception) {
            Log.e(TAG, "teardown error: ${e.message}")
        }
        initialized = false
    }

    fun close() {
        teardown()
    }
}
