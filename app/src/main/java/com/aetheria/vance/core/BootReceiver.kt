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
        Log.i(TAG, "onReceive action=${intent?.action}")
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Thread {
                try {
                    Thread.sleep(2000)
                    runTfliteSmokeTest(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Smoke test thread error: ${e.message}")
                }
            }.start()
        }
    }

    private fun runTfliteSmokeTest(context: Context) {
        try {
            // Just verify the native library loads — don't load the model at boot
            Log.i(TAG, "Smoke test: VanceNpuJni loaded=${com.aetheria.vance.npu.VanceNpuJni.isLoaded()}")
        } catch (e: Throwable) {
            Log.e(TAG, "Smoke test: unexpected error: ${e.message}")
        }
    }
}
