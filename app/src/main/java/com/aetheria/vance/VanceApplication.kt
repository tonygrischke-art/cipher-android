package com.aetheria.vance

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
