package com.aetheria.cipher

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CipherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
