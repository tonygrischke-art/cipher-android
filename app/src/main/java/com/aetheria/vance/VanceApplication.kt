package com.aetheria.vance

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aetheria.vance.core.VanceCoreService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("VanceApplication", "onCreate OK")

        val serviceIntent = Intent(this, VanceCoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
