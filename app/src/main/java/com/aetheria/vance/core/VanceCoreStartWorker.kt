package com.aetheria.vance.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager worker that starts VanceCoreService as a foreground service.
 *
 * BootReceiver enqueues this with a 10-second initial delay to avoid the
 * BOOT_COMPLETED → startForegroundService race that crashes on Android 14+
 * (FGS type restrictions at boot time).
 */
class VanceCoreStartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "VanceCoreStartWorker"
    }

    override fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting VanceCoreService via WorkManager")
            val serviceIntent = Intent(applicationContext, VanceCoreService::class.java)
            applicationContext.startForegroundService(serviceIntent)
            Log.d(TAG, "VanceCoreService startForegroundService called successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VanceCoreService", e)
            Result.failure()
        }
    }
}
