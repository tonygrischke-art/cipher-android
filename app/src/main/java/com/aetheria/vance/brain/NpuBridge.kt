package com.aetheria.vance.brain

import android.content.Context
import android.util.Log

/**
 * JNI bridge to MediaTek Neuron Adapter (libneuron_adapter_mgvi.so).
 *
 * This class provides direct NPU access for TFLite/LiteRT model inference,
 * bypassing NNAPI entirely for maximum performance on MT6878.
 *
 * CRASH FIX: The companion init block now catches UnsatisfiedLinkError and
 * sets `nativeLibAvailable = false`. Callers MUST check this flag before
 * constructing NpuBridge or calling any native method.
 */
class NpuBridge(private val context: Context) {

    companion object {
        private const val TAG = "NpuBridge"

        /**
         * Set to true only if System.loadLibrary("vance_npu") succeeded.
         * If false, native methods will throw UnsatisfiedLinkError — do NOT
         * call them or construct NpuBridge at all.
         */
        @JvmStatic
        var nativeLibAvailable = false
            private set

        // Library paths to try (in order of preference)
        private val NEURON_LIB_PATHS = listOf(
            "/vendor/lib64/libneuron_adapter_mgvi.so",
            "/system/lib64/libneuron_adapter_mgvi.so",
            "/vendor/lib/libneuron_adapter_mgvi.so"
        )

        init {
            try {
                System.loadLibrary("vance_npu")
                nativeLibAvailable = true
                Log.d(TAG, "Native library loaded: libvance_npu.so")
            } catch (e: UnsatisfiedLinkError) {
                nativeLibAvailable = false
                Log.e(TAG, "Failed to load native library libvance_npu.so — NPU unavailable", e)
            }
        }
    }

    private var initialized = false

    /**
     * Initialize the NPU bridge by loading libneuron_adapter_mgvi.so
     * and resolving required symbols.
     *
     * @return true if NPU is available and ready
     */
    fun initialize(): Boolean {
        if (!nativeLibAvailable) {
            Log.w(TAG, "nativeLibAvailable=false, cannot initialize NPU")
            return false
        }
        if (initialized) return true

        // Check if any Neuron library path exists
        val libExists = NEURON_LIB_PATHS.any { path ->
            java.io.File(path).exists().also { exists ->
                if (exists) Log.d(TAG, "Found Neuron adapter at: $path")
            }
        }

        if (!libExists) {
            Log.w(TAG, "No Neuron adapter found. NPU unavailable.")
            return false
        }

        initialized = nativeInitNpu()
        Log.i(TAG, "NPU initialization result: $initialized")
        return initialized
    }

    /**
     * Check if NPU is available without initializing.
     */
    fun isAvailable(): Boolean = initialized

    /**
     * Run inference on the NPU.
     *
     * @param modelPath Absolute path to the TFLite/LiteRT model file
     * @param inputText Input text/prompt for the model
     * @return Generated text, or error string prefixed with "NPU_"
     */
    fun runInference(modelPath: String, inputText: String): String {
        if (!initialized) {
            Log.w(TAG, "NPU not initialized, cannot run inference")
            return "NPU_NOT_INITIALIZED"
        }

        val startTime = System.currentTimeMillis()
        val result = nativeRunInference(modelPath, inputText)
        val elapsed = System.currentTimeMillis() - startTime

        Log.d(TAG, "NPU inference completed in ${elapsed}ms")
        return result
    }

    /**
     * Get the last error message from native layer.
     */
    fun getLastError(): String = nativeGetLastError()

    /**
     * Shutdown the NPU bridge and free resources.
     */
    fun shutdown() {
        if (initialized) {
            nativeShutdownNpu()
            initialized = false
            Log.i(TAG, "NPU bridge shutdown")
        }
    }

    // Native methods — only safe to call if nativeLibAvailable == true
    private external fun nativeInitNpu(): Boolean
    private external fun nativeShutdownNpu()
    private external fun nativeIsNpuAvailable(): Boolean
    private external fun nativeRunInference(modelPath: String, inputText: String): String
    private external fun nativeGetLastError(): String
}
