package com.aetheria.vance.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aetheria.vance.brain.NeuronBridge
import java.io.File
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Boot/update received — action=${intent.action}")
            runNpuSmokeTest(context)
        }
    }

    private fun runNpuSmokeTest(context: Context) {
        if (!NeuronBridge.isAvailable) {
            Log.w(TAG, "NPU: NeuronBridge.isAvailable=false")
            return
        }
        Log.i(TAG, "NPU: NeuronBridge.isAvailable=true, running smoke test...")

        Thread {
            try {
                // Use hermes_int8.tflite from app filesDir (80MB valid TFLite)
                val modelFile = File(context.filesDir, "hermes_int8.tflite")
                val modelPath = if (modelFile.exists()) {
                    Log.i(TAG, "NPU: using hermes_int8.tflite (${modelFile.length()/1024/1024}MB)")
                    modelFile.absolutePath
                } else {
                    Log.w(TAG, "NPU: hermes_int8.tflite not in filesDir, trying /data/local/tmp/cipher_models/")
                    "/data/local/tmp/cipher_models/hermes_int8.tflite"
                }
                val cacheDir = context.cacheDir.absolutePath + "/neuron_test"

                Log.i(TAG, "NPU: nativeInit model=$modelPath cache=$cacheDir")
                val handle = NeuronBridge.nativeInit(modelPath, cacheDir)
                Log.i(TAG, "NPU: nativeInit handle=$handle")
                if (handle != 0L) {
                    val result = NeuronBridge.nativeInfer(handle, "Hello")
                    Log.i(TAG, "NPU: nativeInfer result='$result'")
                    NeuronBridge.nativeClose(handle)
                    Log.i(TAG, "NPU: smoke test COMPLETE")
                } else {
                    Log.e(TAG, "NPU: nativeInit returned 0")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "NPU: smoke test exception: ${e.message}")
            }
        }.start()
    }
}
