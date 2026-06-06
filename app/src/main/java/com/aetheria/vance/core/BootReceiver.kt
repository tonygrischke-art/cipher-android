package com.aetheria.vance.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aetheria.vance.brain.NeuronBridge
import java.io.File

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i("BootReceiver", "NPU: onReceive action=${intent?.action}")
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i(TAG, "Boot/update received — scheduling NPU smoke test")
            try {
                runNpuSmokeTest(context)
            } catch (e: Throwable) {
                Log.e(TAG, "NPU smoke test failed: ${e.message}")
            }
        }
    }

    private fun runNpuSmokeTest(context: Context) {
        Log.i(TAG, "NPU: checking NeuronBridge.isAvailable...")
        val available = try {
            NeuronBridge.isAvailable
        } catch (e: Throwable) {
            Log.e(TAG, "NPU: NeuronBridge.isAvailable threw: ${e.message}")
            false
        }
        Log.i(TAG, "NPU: NeuronBridge.isAvailable=$available")
        if (!available) return

        Thread {
            try {
                val modelFile = File(context.filesDir, "mobilenet_test.tflite")
                val modelPath = if (modelFile.exists()) {
                    Log.i(TAG, "NPU: using mobilenet_test.tflite (${modelFile.length() / 1024}KB)")
                    modelFile.absolutePath
                } else {
                    Log.w(TAG, "NPU: mobilenet_test.tflite not in filesDir, trying /data/local/tmp/cipher_models/")
                    "/data/local/tmp/cipher_models/mobilenet_test.tflite"
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
