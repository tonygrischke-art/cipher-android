package com.aetheria.vance.brain

import android.util.Log

/**
 * Direct NeuronAdapter bridge for MediaTek NPU inference.
 *
 * Loads libneuron_bridge.so which dlopen's libneuronusdk_adapter.mtk.so
 * and drives the NeuroPilot API directly — no intermediate wrappers.
 *
 * Use [isAvailable] to check if native library loaded successfully.
 * If false, NPU is unavailable and LiteRTEngine falls back to MediaPipe CPU.
 */
object NeuronBridge {
    private const val TAG = "NeuronBridge"

    /** True once libneuron_bridge.so is loaded. */
    val isAvailable: Boolean = try {
        System.loadLibrary("neuron_bridge")
        Log.i(TAG, "libneuron_bridge.so loaded — NeuronAdapter ready")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "libneuron_bridge.so not in APK — NPU disabled")
        false
    } catch (e: Throwable) {
        Log.e(TAG, "Unexpected error loading neuron_bridge: ${e.message}")
        false
    }

    /**
     * Load model, compile for NPU, create execution session.
     * @param modelPath Absolute path to .tflite/.dla/.litertlm model file
     * @param cacheDir  Directory for neuron_cache/ compiled network cache
     * @return Opaque session handle (0 on failure)
     */
    @JvmStatic
    external fun nativeInit(modelPath: String, cacheDir: String): Long

    /**
     * Run inference.
     * @param handle  Session handle from nativeInit
     * @param prompt  Input text prompt
     * @return Generated text output
     */
    @JvmStatic
    external fun nativeInfer(handle: Long, prompt: String): String

    /**
     * Free all native resources for a session.
     * @param handle Session handle from nativeInit
     */
    @JvmStatic
    external fun nativeClose(handle: Long)
}
