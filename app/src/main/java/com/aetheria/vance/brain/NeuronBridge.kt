package com.aetheria.vance.brain

import android.content.Context
import android.util.Log

/**
 * JNI wrapper for libneuron_bridge.so
 * Loads the native library and exposes Phase 2 NPU inference via TFLite C API + NNAPI shim.
 *
 * SAFE LOADING: All native library loads are wrapped in try/catch.
 * If the library is unavailable, methods degrade gracefully instead of crashing.
 */
class NeuronBridge {

    companion object {
        private const val TAG = "NeuronBridge"
        @Volatile private var sLoaded = false
        @Volatile private var sLoadAttempted = false

        /**
         * Load libneuron_bridge.so safely.
         * Idempotent — safe to call multiple times.
         * @return true if library loaded successfully
         */
        @Synchronized
        fun loadLibrary(): Boolean {
            if (sLoadAttempted) return sLoaded
            sLoadAttempted = true
            try {
                System.loadLibrary("neuron_bridge")
                sLoaded = true
                Log.i(TAG, "libneuron_bridge.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "libneuron_bridge.so load failed: ${e.message}")
                sLoaded = false
            } catch (e: SecurityException) {
                Log.e(TAG, "libneuron_bridge.so security error: ${e.message}")
                sLoaded = false
            }
            return sLoaded
        }

        fun isLoaded(): Boolean = sLoaded

        /**
         * Initialize the NPU adapter by loading libneuron_adapter_mgvi.so.
         * @return true if adapter loaded successfully
         */
        @JvmStatic
        external fun initAdapter(): Boolean

        /**
         * Check if NPU hardware is available.
         */
        fun isAvailable(): Boolean {
            if (!sLoaded) return false
            return try {
                nativeIsAvailable()
            } catch (e: Exception) {
                Log.e(TAG, "nativeIsAvailable failed: ${e.message}")
                false
            }
        }

        /**
         * Initialize NPU session with model.
         * @return Opaque session handle (0 = failure)
         */
        @JvmStatic
        external fun nativeInit(modelPath: String, cacheDir: String): Long

        /**
         * Run inference on initialized session.
         */
        @JvmStatic
        external fun nativeInfer(handle: Long, prompt: String): String

        /**
         * Clean up session resources.
         */
        @JvmStatic
        external fun nativeClose(handle: Long)

        @JvmStatic
        private external fun nativeIsAvailable(): Boolean
    }
}
