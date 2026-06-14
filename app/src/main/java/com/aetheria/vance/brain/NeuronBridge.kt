package com.aetheria.vance.brain

import android.util.Log

/**
 * JNI wrapper for libneuron_bridge.so
 * Loads libneuron_adapter_mgvi.so from /vendor/lib64/ — the confirmed working NPU adapter.
 * SAFE LOADING: All native library loads wrapped in try/catch.
 */
object NeuronBridge {
    private var isLoaded = false
    private var npuHandle: Long = 0L

    @Volatile private var initState: NpuInitState = NpuInitState.UNINITIALIZED

    enum class NpuInitState { UNINITIALIZED, LOADING, READY, FAILED }

    fun initialize(): Boolean {
        if (initState == NpuInitState.READY) return true
        if (initState == NpuInitState.FAILED) return false

        synchronized(this) {
            initState = NpuInitState.LOADING
            return try {
                val handle = loadNativeAdapter()
                if (handle != 0L) {
                    npuHandle = handle
                    isLoaded = true
                    initState = NpuInitState.READY
                    Log.i("NeuronBridge", "NPU adapter loaded via libneuron_adapter_mgvi.so")
                    true
                } else {
                    initState = NpuInitState.FAILED
                    Log.e("NeuronBridge", "dlopen returned null handle — NPU unavailable")
                    false
                }
            } catch (e: UnsatisfiedLinkError) {
                initState = NpuInitState.FAILED
                Log.e("NeuronBridge", "UnsatisfiedLinkError: ${e.message}")
                false
            } catch (e: SecurityException) {
                initState = NpuInitState.FAILED
                Log.e("NeuronBridge", "SecurityException: ${e.message}")
                false
            } catch (t: Throwable) {
                initState = NpuInitState.FAILED
                Log.e("NeuronBridge", "Unexpected NPU init failure: ${t.message}")
                false
            }
        }
    }

    fun isReady() = initState == NpuInitState.READY

    private external fun loadNativeAdapter(): Long
    external fun runInference(inputData: ByteArray): ByteArray?
    external fun destroyAdapter(handle: Long)

    init {
        try {
            System.loadLibrary("neuron_bridge")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NeuronBridge", "libneuron_bridge.so not found: ${e.message}")
        }
    }
}
