package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NPU inference engine using libneuron_bridge.so (Phase 2: TFLite C API + NNAPI SL shim).
 * Loads model via TFLite C API, delegates to MediaTek Neuron Adapter (MT6878 NPU).
 * No MediaPipe GenAI dependency at runtime — pure native.
 *
 * This class wraps NeuronBridge and exposes a simple init/generate/close API.
 * JNI methods are declared in NeuronBridge (companion object with external fun).
 */
class NpuEngine(private val context: Context) {

    companion object {
        private const val TAG = "NpuEngine"
        const val MODEL_FILENAME = "qwen05.tflite"
    }

    private var sessionHandle: Long = 0L
    var isInitialised = false
        private set

    init {
        // Load native library on construction
        NeuronBridge.loadLibrary()
    }

    fun init(modelPath: String) {
        if (!NeuronBridge.isLoaded()) {
            Log.e(TAG, "NeuronBridge library not loaded")
            isInitialised = false
            return
        }

        this.modelPath = modelPath
        Log.i(TAG, "Init from: $modelPath")

        // Probe NPU availability
        val available = NeuronBridge.isAvailable()
        Log.i(TAG, "NPU available: $available")
        if (!available) {
            Log.w(TAG, "NPU not available, engine will not initialise")
            isInitialised = false
            return
        }

        // Create session
        val cacheDir = context.cacheDir.absolutePath
        sessionHandle = NeuronBridge.nativeInit(modelPath, cacheDir)
        if (sessionHandle == 0L) {
            Log.e(TAG, "nativeInit failed")
            isInitialised = false
            return
        }

        isInitialised = true
        Log.i(TAG, "NpuEngine initialised successfully ✓ (handle=$sessionHandle)")
    }

    suspend fun generate(prompt: String): String? {
        if (!isInitialised || sessionHandle == 0L) return null
        return withContext(Dispatchers.Default) {
            try {
                val result = NeuronBridge.nativeInfer(sessionHandle, prompt)
                Log.i(TAG, "Inference complete (${result?.length ?: 0} chars)")
                result
            } catch (e: Exception) {
                Log.e(TAG, "generate() failed: ${e.message}", e)
                null
            }
        }
    }

    fun close() {
        if (sessionHandle != 0L) {
            NeuronBridge.nativeClose(sessionHandle)
            sessionHandle = 0L
        }
        isInitialised = false
        Log.i(TAG, "NpuEngine closed")
    }

    private var modelPath: String = ""
}
