package com.aetheria.vance.brain

import android.content.Context
import android.util.Log

/**
 * JNI wrapper for libneuron_bridge.so
 * Loads the native library and exposes Phase 2 NPU inference via TFLite C API + NNAPI SL shim.
 */
class NeuronBridge {

    companion object {
        private const val TAG = "NeuronBridge"
        private var sLoaded = false

        /**
         * Loads libneuron_bridge.so. Must be called before any native methods.
         * Safe to call multiple times.
         */
        fun loadLibrary(): Boolean {
            if (sLoaded) return true
            return try {
                System.loadLibrary("neuron_bridge")
                sLoaded = true
                Log.i(TAG, "libneuron_bridge.so loaded ✓")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libneuron_bridge.so: ${e.message}")
                false
            }
        }

        fun isLoaded(): Boolean = sLoaded

        /**
         * Check if NPU hardware is available (wrapper for native method).
         */
        fun isAvailable(): Boolean = nativeIsAvailable()

        /**
         * Initialize NPU session with model.
         * @param modelPath Absolute path to .tflite model file
         * @param cacheDir  Cache directory for delegate (can be app cache dir)
         * @return Opaque session handle (0 = failure)
         */
        @JvmStatic
        external fun nativeInit(modelPath: String, cacheDir: String): Long

        /**
         * Run inference on initialized session.
         * @param handle Session handle from nativeInit
         * @param prompt Input prompt string
         * @return Model output as string, or error message
         */
        @JvmStatic
        external fun nativeInfer(handle: Long, prompt: String): String

        /**
         * Clean up session resources.
         * @param handle Session handle from nativeInit
         */
        @JvmStatic
        external fun nativeClose(handle: Long)

        /**
         * Check if NPU hardware is available (adapter loads, devices found).
         * This does NOT create a full session - just probes the adapter.
         */
        @JvmStatic
        external fun nativeIsAvailable(): Boolean
    }
}