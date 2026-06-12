package com.aetheria.vance.wake

import android.util.Log

/**
 * JNI bridge to the wake_npu_engine native library.
 * Handles TFLite wake word classifier model inference via NNAPI delegate
 * on the MediaTek NPU 655 (MT6878).
 *
 * Model must be INT8 post-training quantized. FP32 will fail with
 * disallow_nnapi_cpu = 1 enforced by the NNAPI delegate.
 *
 * Model path convention: /data/local/tmp/cipher_models/wake_word_int8.tflite
 */
class WakeNPUEngine {

    companion object {
        private const val TAG = "WakeNPUEngine"

        init {
            try {
                System.loadLibrary("wake_npu_engine")
                Log.i(TAG, "wake_npu_engine loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load wake_npu_engine: ${e.message}")
            }
        }
    }

    /**
     * Load and initialize the INT8 TFLite wake word model.
     * @param modelPath absolute path to .tflite file
     * @return true if NPU delegate initialized and tensors allocated
     */
    external fun initializeModel(modelPath: String): Boolean

    /**
     * Run inference on a single MFCC feature frame.
     * @param mfccInput float array of MFCC coefficients
     * @return confidence score 0.0–1.0, or -1.0 on error
     */
    external fun runInference(mfccInput: FloatArray): Float

    /**
     * Release all native resources. Call from onDestroy / cleanup.
     */
    external fun terminateEngine()
}
