package com.aetheria.vance

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import android.util.Log
import com.aetheria.vance.core.VanceCoreService
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class VanceApplication : Application() {

    companion object {
        private const val TAG = "VanceApplication"
    }

    override fun onCreate() {
        super.onCreate()

        // Crash handler — write stack traces to files dir for debugging
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val log = File(filesDir, "cipher_crash_${System.currentTimeMillis()}.txt")
                log.writeText("Thread: ${thread.name}\n${throwable.stackTraceToString()}")
                Log.e(TAG, "Uncaught exception on ${thread.name}: ${throwable.message}")
            } catch (_: Exception) {
                // Don't crash in the crash handler
            }
            // Re-throw to let system handle it (will show crash dialog / restart)
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(thread, throwable)
        }

        // StrictMode — detect threading violations and resource leaks in debug builds
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }

        Log.i(TAG, "onCreate OK")

        // Start core service
        val serviceIntent = Intent(this, VanceCoreService::class.java)
        startForegroundService(serviceIntent)
    }
}
