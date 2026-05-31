package com.aetheria.cipher.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aetheria.cipher.ui.MainActivity
import com.aetheria.cipher.R
import com.aetheria.cipher.brain.BrainRouter
import com.aetheria.cipher.context.ContextEngine
import com.aetheria.cipher.voice.VoicePipeline
import com.aetheria.cipher.voice.WakeWordService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CipherCoreService : Service() {

    @Inject lateinit var brainRouter: BrainRouter
    @Inject lateinit var contextEngine: ContextEngine
    @Inject lateinit var voicePipeline: VoicePipeline

    companion object {
        private const val TAG = "CipherCore"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cipher_core_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CipherCoreService created")
        createNotificationChannel()
        startForeground()
        startSubsystems()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            "ACTION_WAKE_WORD_DETECTED" -> handleWakeWord(intent)
            "ACTION_PROCESS_TRANSCRIPT" -> {
                val transcript = intent.getStringExtra("transcript") ?: ""
                if (transcript.isNotBlank()) handleTranscript(transcript)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cipher Core Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Always-on Cipher agent service" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cipher")
            .setContentText("Listening...")
            .setSmallIcon(R.drawable.cipher_orb)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSubsystems() {
        // Start WakeWordService
        val wakeIntent = Intent(this, WakeWordService::class.java)
        startService(wakeIntent)
        Log.d(TAG, "WakeWordService started")
    }

    private fun handleWakeWord(intent: Intent) {
        val wakeWord = intent.getStringExtra("wake_word") ?: ""
        Log.d(TAG, "Wake word detected: $wakeWord")
        voicePipeline.startListening(onTranscript = { transcript ->
            handleTranscript(transcript)
        })
    }

    private fun handleTranscript(transcript: String) {
        val context = contextEngine.getCurrentContext()
        // TODO: bridge suspend function call properly
        // brainRouter.process(transcript, context) { result ->
        //     voicePipeline.speak(result)
        // }
    }
}
