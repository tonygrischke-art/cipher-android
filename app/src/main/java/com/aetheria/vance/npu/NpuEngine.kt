package com.aetheria.vance.npu

import android.content.Context
import android.util.Log
import com.aetheria.vance.brain.NeuronBridge
import java.io.File

class NpuEngine(private val context: Context) {

    private var llmInference: Any? = null
    private var isNpuActive = false

    fun initialize(): Boolean {
        // 1. Check RAM first
        val runtime = Runtime.getRuntime()
        val availMB = runtime.freeMemory() / 1048576
        if (availMB < 200) {
            Log.e("NpuEngine", "Insufficient RAM (${availMB}MB) — skipping NPU init")
            return false
        }

        // 2. Verify model file exists
        val modelPath = "/data/local/tmp/cipher_models/cipher_model.task"
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e("NpuEngine", "Model file missing at $modelPath — cannot init NPU")
            return false
        }
        Log.i("NpuEngine", "Model found: ${modelFile.length() / 1048576}MB")

        // 3. Init NeuronBridge
        if (!NeuronBridge.initialize()) {
            Log.w("NpuEngine", "NeuronBridge unavailable — will fall back to llama.cpp")
            return false
        }

        // 4. NeuronBridge is ready — NPU inference available via JNI
        isNpuActive = true
        Log.i("NpuEngine", "NPU engine ready via NeuronBridge")
        return true
    }

    fun isActive() = isNpuActive

    fun runInference(prompt: String): String? {
        if (!isNpuActive) return null
        return try {
            val result = NeuronBridge.runInference(prompt.toByteArray())
            result?.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("NpuEngine", "Inference error: ${e.message}")
            null
        }
    }

    fun destroy() {
        try {
            NeuronBridge.destroyAdapter(0L)
        } catch (e: Exception) {
            Log.e("NpuEngine", "destroy error: ${e.message}")
        }
        llmInference = null
        isNpuActive = false
    }
}
