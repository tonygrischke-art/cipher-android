package com.aetheria.cipher.shizuku

import android.content.Intent
import android.os.IBinder
import android.util.Log
import rikka.shizuku.ShizukuUserService

class CipherUserService : ShizukuUserService() {

    companion object {
        private const val TAG = "CipherUserService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CipherUserService created")
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "CipherUserService onBind")
        return super.onBind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "CipherUserService destroyed")
        super.onDestroy()
    }
}
