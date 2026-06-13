package com.aetheria.vance.npu

import android.util.Log

/**
 * JNI bindings for vance_npu_engine native library.
 * Safe loading — if the native lib is unavailable, methods are no-ops.
 */
object VanceNpuJni {
    private const val TAG = "VanceNpuJni"
    @Volatile private var sLoaded = false
    @Volatile private var sLoadAttempted = false

    init {
        loadLibrary()
    }

    @Synchronized
    private fun loadLibrary() {
        if (sLoadAttempted) return
        sLoadAttempted = true
        try {
            System.loadLibrary("vance_npu_engine")
            sLoaded = true
            Log.i(TAG, "vance_npu_engine loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "vance_npu_engine load failed: ${e.message}")
            sLoaded = false
        } catch (e: SecurityException) {
            Log.e(TAG, "vance_npu_engine security error: ${e.message}")
            sLoaded = false
        }
    }

    fun isLoaded(): Boolean = sLoaded

    fun initializeModel(modelPath: String): Boolean {
        if (!sLoaded) {
            Log.w(TAG, "initializeModel: native lib not loaded")
            return false
        }
        return try {
            nativeInitializeModel(modelPath)
        } catch (e: Exception) {
            Log.e(TAG, "nativeInitializeModel failed: ${e.message}")
            false
        }
    }

    fun runInference(prompt: String): String? {
        if (!sLoaded) {
            Log.w(TAG, "runInference: native lib not loaded")
            return null
        }
        return try {
            nativeRunInference(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "nativeRunInference failed: ${e.message}")
            null
        }
    }

    fun terminateEngine() {
        if (!sLoaded) return
        try {
            nativeTerminateEngine()
        } catch (e: Exception) {
            Log.e(TAG, "nativeTerminateEngine failed: ${e.message}")
        }
    }

    @JvmStatic
    private external fun nativeInitializeModel(modelPath: String): Boolean

    @JvmStatic
    private external fun nativeRunInference(prompt: String): String?

    @JvmStatic
    private external fun nativeTerminateEngine()
}
