package com.aetheria.vance.wake

import android.util.Log

/**
 * JNI bridge to the wake_npu_engine native library.
 * Handles TFLite wake word classifier model inference via NNAPI delegate
 * on the MediaTek NPU 655 (MT6878).
 *
 * Safe loading — if the native lib is unavailable, methods are no-ops.
 */
class WakeNPUEngine {

    companion object {
        private const val TAG = "WakeNPUEngine"
        @Volatile private var sLoaded = false
        @Volatile private var sLoadAttempted = false

        @Synchronized
        private fun loadLibrary() {
            if (sLoadAttempted) return
            sLoadAttempted = true
            try {
                System.loadLibrary("wake_npu_engine")
                sLoaded = true
                Log.i(TAG, "wake_npu_engine loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "wake_npu_engine load failed: ${e.message}")
                sLoaded = false
            } catch (e: SecurityException) {
                Log.e(TAG, "wake_npu_engine security error: ${e.message}")
                sLoaded = false
            }
        }

        init {
            loadLibrary()
        }

        fun isLoaded(): Boolean = sLoaded

        @JvmStatic
        private external fun nativeInitializeModel(modelPath: String): Boolean

        @JvmStatic
        private external fun nativeRunInference(mfccInput: FloatArray): Float

        @JvmStatic
        private external fun nativeTerminateEngine()
    }

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

    fun runInference(mfccInput: FloatArray): Float {
        if (!sLoaded) return -1f
        return try {
            nativeRunInference(mfccInput)
        } catch (e: Exception) {
            Log.e(TAG, "nativeRunInference failed: ${e.message}")
            -1f
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
}
