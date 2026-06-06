package com.aetheria.vance.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aetheria.vance.brain.NeuronBridge
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Data
import androidx.work.Constraints
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "BOOT_COMPLETED received — scheduling VanceCoreStartWorker with 10s delay")
            val workRequest = OneTimeWorkRequestBuilder<VanceCoreStartWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }

        // NPU smoke test — runs on every boot+launch
        if (NeuronBridge.isAvailable) {
            Log.i(TAG, "NPU: NeuronBridge.isAvailable=true, running smoke test...")
            Thread {
                try {
                    val modelPath = "/data/local/tmp/cipher_models/mobile_actions_q8_ekv1024.litertlm"
                    val cacheDir = context.cacheDir.absolutePath + "/neuron_test"
                    Log.i(TAG, "NPU: Calling nativeInit with model=$modelPath")
                    val handle = NeuronBridge.nativeInit(modelPath, cacheDir)
                    Log.i(TAG, "NPU: nativeInit returned handle=$handle")
                    if (handle != 0L) {
                        val result = NeuronBridge.nativeInfer(handle, "Hello")
                        Log.i(TAG, "NPU: nativeInfer result: $result")
                        NeuronBridge.nativeClose(handle)
                        Log.i(TAG, "NPU: smoke test COMPLETE")
                    } else {
                        Log.e(TAG, "NPU: nativeInit returned 0 — compilation failed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "NPU: smoke test exception", e)
                }
            }.start()
        } else {
            Log.w(TAG, "NPU: NeuronBridge.isAvailable=false")
        }
    }
}
