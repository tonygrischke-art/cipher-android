package com.aetheria.vance.shizuku

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class VanceUserService : Service() {

    companion object {
        private const val TAG = "VanceUserService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VanceUserService created")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "VanceUserService onBind — returning null (no binder API needed)")
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "VanceUserService destroyed")
        super.onDestroy()
    }
}
