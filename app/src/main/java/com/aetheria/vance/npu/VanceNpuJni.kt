package com.aetheria.vance.npu

import android.util.Log

object VanceNpuJni {
    private const val TAG = "VanceNpuJni"

    init {
        try {
            System.loadLibrary("vance_npu_engine")
            Log.i(TAG, "vance_npu_engine loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load vance_npu_engine: ${e.message}")
        }
    }

    external fun initializeModel(modelPath: String): Boolean
    external fun runInference(prompt: String): String?
    external fun terminateEngine()
}
