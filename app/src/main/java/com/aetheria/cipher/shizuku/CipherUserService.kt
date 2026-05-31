package com.aetheria.cipher.shizuku

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class CipherUserService : Service() {

    companion object {
        private const val TAG = "CipherUserService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CipherUserService created")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "CipherUserService onBind — returning null (no binder API needed)")
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "CipherUserService destroyed")
        super.onDestroy()
    }
}
