package com.aetheria.vance.brain

import android.util.Log

/**
 * JNI wrapper for libneuron_bridge.so
 * Loads libneuron_adapter_mgvi.so and manages TFLite NPU inference sessions
 * via the standard NNAPI delegate path.
 *
 * JNI chain: TFLite C API → NnApiDelegate → System NNAPI APEX → MTK SL driver → NPU
 *
 * SAFE LOADING: All native library loads wrapped in try/catch.
 * If the library is unavailable, methods degrade gracefully instead of crashing.
 */
object NeuronBridge {
    private const val TAG = "NeuronBridge"

    @Volatile private var initState: NpuInitState = NpuInitState.UNINITIALIZED

    enum class NpuInitState { UNINITIALIZED, LOADING, READY, FAILED }

    fun initialize(): Boolean {
        if (initState == NpuInitState.READY) return true
        if (initState == NpuInitState.FAILED) return false

        synchronized(this) {
            initState = NpuInitState.LOADING
            return try {
                // Step 1: Load the MGVI adapter (dlopen libneuron_adapter_mgvi.so)
                val adapterOk = initAdapter()
                if (!adapterOk) {
                    Log.e(TAG, "initAdapter() returned false — NPU unavailable")
                    initState = NpuInitState.FAILED
                    return false
                }
                Log.i(TAG, "libneuron_adapter_mgvi.so loaded successfully")

                // Step 2: Verify TFLite + NNAPI delegate are available
                val available = nativeIsAvailable()
                if (!available) {
                    Log.w(TAG, "TFLite/NNAPI not available — NPU will use CPU fallback")
                    // Still mark as READY — TFLite CPU path works without NPU
                }

                initState = NpuInitState.READY
                Log.i(TAG, "NeuronBridge ready — NPU adapter loaded")
                true
            } catch (e: UnsatisfiedLinkError) {
                initState = NpuInitState.FAILED
                Log.e(TAG, "UnsatisfiedLinkError during init: ${e.message}")
                false
            } catch (e: Exception) {
                initState = NpuInitState.FAILED
                Log.e(TAG, "Unexpected init failure: ${e.message}")
                false
            }
        }
    }

    fun isReady() = initState == NpuInitState.READY

    /**
     * Load the TFLite model and create an NPU inference session.
     * @param modelPath Path to .tflite model file
     * @param cacheDir Cache directory for model compilation
     * @return Session handle (0 = failure)
     */
    fun createSession(modelPath: String, cacheDir: String): Long {
        if (initState != NpuInitState.READY) {
            Log.e(TAG, "createSession: NeuronBridge not ready (state=$initState)")
            return 0L
        }
        return try {
            val handle = nativeInit(modelPath, cacheDir)
            if (handle != 0L) {
                Log.i(TAG, "NPU session created: model=$modelPath handle=$handle")
            } else {
                Log.e(TAG, "nativeInit returned 0 — session creation failed")
            }
            handle
        } catch (e: Exception) {
            Log.e(TAG, "createSession error: ${e.message}")
            0L
        }
    }

    /**
     * Run inference on an existing session.
     * @param handle Session handle from createSession()
     * @param prompt Input string
     * @return Inference result string, or null on error
     */
    fun runInference(handle: Long, prompt: String): String? {
        if (initState != NpuInitState.READY || handle == 0L) return null
        return try {
            nativeInfer(handle, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "runInference error: ${e.message}")
            null
        }
    }

    /**
     * Destroy an NPU session and free resources.
     */
    fun destroySession(handle: Long) {
        if (handle == 0L) return
        try {
            nativeClose(handle)
            Log.i(TAG, "NPU session destroyed: handle=$handle")
        } catch (e: Exception) {
            Log.e(TAG, "destroySession error: ${e.message}")
        }
    }

    // ── JNI methods (must match neuron_bridge.cpp signatures) ──────────

    /** Load libneuron_adapter_mgvi.so via dlopen */
    @JvmStatic
    external fun initAdapter(): Boolean

    /** Check if TFLite C API + NNAPI delegate are available */
    @JvmStatic
    private external fun nativeIsAvailable(): Boolean

    /** Load model + create interpreter with NNAPI delegate */
    @JvmStatic
    external fun nativeInit(modelPath: String, cacheDir: String): Long

    /** Run inference on session */
    @JvmStatic
    external fun nativeInfer(handle: Long, prompt: String): String

    /** Destroy interpreter and free resources */
    @JvmStatic
    external fun nativeClose(handle: Long)

    init {
        try {
            System.loadLibrary("neuron_bridge")
            Log.i(TAG, "libneuron_bridge.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libneuron_bridge.so not found: ${e.message}")
            initState = NpuInitState.FAILED
        }
    }
}
