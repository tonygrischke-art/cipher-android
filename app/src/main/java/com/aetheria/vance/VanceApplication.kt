package com.aetheria.vance

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("VanceApplication", "onCreate OK")
    }
}
