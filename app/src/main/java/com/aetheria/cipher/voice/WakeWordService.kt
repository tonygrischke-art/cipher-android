package com.aetheria.cipher.voice

import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.*

class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "cipher_wake_channel"
        private const val ACCESS_KEY = "" // Set from encrypted prefs
    }

    private var porcupine: Porcupine? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WakeWordService created")
        createNotificationChannel()
        startForeground()
        initializePorcupine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WakeWordService started")
        return START_STICKY
    }

    override fun onDestroy() {
        porcupine?.delete()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cipher Wake Word",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Wake word detection service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cipher")
            .setContentText("Wake word listener active")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializePorcupine() {
        try {
            // TODO: Initialize Picovoice Porcupine with valid access key and custom wake word
            // porcupine = Porcupine.Builder()
            //     .setAccessKey(ACCESS_KEY)
            //     .setKeyword(Porcupine.BuiltInKeyword.ALEXA)
            //     .build(applicationContext)
            //
            // Start audio recording and process frames
            Log.d(TAG, "Porcupine initialized (stub)")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine init failed", e)
        }
    }

    private fun onWakeWordDetected(keywordIndex: Int) {
        Log.d(TAG, "Wake word detected! index=$keywordIndex")

        // Notify CipherCore to start voice pipeline
        val intent = Intent(this, com.aetheria.cipher.core.CipherCoreService::class.java).apply {
            action = "ACTION_WAKE_WORD_DETECTED"
            putExtra("wake_word", "hey_cipher")
        }
        startService(intent)
    }
}
