package com.aetheria.vance.npu

import android.content.Context
import android.util.Log
import com.aetheria.vance.brain.NeuronBridge
import java.io.File

class NpuEngine(private val context: Context) {

    private const val TAG = "NpuEngine"
    private var sessionHandle: Long = 0L
    private var isNpuActive = false

    fun initialize(): Boolean {
        // 1. Check RAM first
        val runtime = Runtime.getRuntime()
        val availMB = runtime.freeMemory() / 1048576
        if (availMB < 200) {
            Log.e(TAG, "Insufficient RAM (${availMB}MB) — skipping NPU init")
            return false
        }

        // 2. Verify model file exists
        val modelPath = "/data/local/tmp/cipher_models/cipher_qwen.task"
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file missing at $modelPath — cannot init NPU")
            return false
        }
        Log.i(TAG, "Model found: ${modelFile.length() / 1048576}MB")

        // 3. Init NeuronBridge
        if (!NeuronBridge.initialize()) {
            Log.w(TAG, "NeuronBridge unavailable — will fall back to llama.cpp")
            return false
        }

        // 4. Create NPU inference session
        val cacheDir = context.cacheDir.absolutePath
        sessionHandle = NeuronBridge.createSession(modelPath, cacheDir)
        if (sessionHandle == 0L) {
            Log.e(TAG, "Failed to create NPU session")
            return false
        }

        isNpuActive = true
        Log.i(TAG, "NPU engine ready — session handle=$sessionHandle")
        return true
    }

    fun isActive() = isNpuActive

    fun runInference(prompt: String): String? {
        if (!isNpuActive || sessionHandle == 0L) return null
        return try {
            NeuronBridge.runInference(sessionHandle, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
            null
        }
    }

    fun destroy() {
        if (sessionHandle != 0L) {
            NeuronBridge.destroySession(sessionHandle)
            sessionHandle = 0L
        }
        isNpuActive = false
    }
}
