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
import com.aetheria.cipher.R
import com.aetheria.cipher.brain.BrainRouter
import com.aetheria.cipher.brain.LiteRTEngine
import com.aetheria.cipher.context.ContextEngine
import com.aetheria.cipher.ui.MainActivity
import com.aetheria.cipher.voice.VoicePipeline
import com.aetheria.cipher.voice.WakeWordService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CipherCoreService : Service() {

    companion object {
        private const val TAG = "CipherCore"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cipher_core_channel"

        const val ACTION_WAKE_WORD_DETECTED = "ACTION_WAKE_WORD_DETECTED"
        const val ACTION_PROCESS_TRANSCRIPT = "ACTION_PROCESS_TRANSCRIPT"
    }

    @Inject lateinit var brainRouter: BrainRouter
    @Inject lateinit var contextEngine: ContextEngine
    @Inject lateinit var voicePipeline: VoicePipeline
    @Inject lateinit var liteRTEngine: LiteRTEngine
    @Inject lateinit var actionExecutor: com.aetheria.cipher.actions.ActionExecutor

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CipherCoreService created")
        createNotificationChannel()
        startForeground()
        initializeSubsystems()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_WAKE_WORD_DETECTED -> handleWakeWord(intent)
            ACTION_PROCESS_TRANSCRIPT -> {
                val transcript = intent.getStringExtra("transcript") ?: ""
                if (transcript.isNotBlank()) handleTranscript(transcript)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "CipherCoreService destroying")
        voicePipeline.destroy()
        liteRTEngine.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Initialization ─────────────────────────────────────────────

    private fun initializeSubsystems() {
        Log.d(TAG, "Initializing subsystems")
        voicePipeline.initialize()

        // Start WakeWordService
        try {
            val wakeIntent = Intent(this, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(wakeIntent)
            } else {
                startService(wakeIntent)
            }
            Log.d(TAG, "WakeWordService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WakeWordService", e)
        }
    }

    // ── Intent handlers ────────────────────────────────────────────

    private fun handleWakeWord(intent: Intent) {
        val wakeWord = intent.getStringExtra("wake_word") ?: "unknown"
        Log.d(TAG, "Wake word detected: $wakeWord")

        voicePipeline.startListening(
            onTranscript = { transcript ->
                handleTranscript(transcript)
            },
            onTimeout = {
                Log.d(TAG, "Listening timeout — returning to idle")
            }
        )
    }

    private fun handleTranscript(transcript: String) {
        Log.d(TAG, "Processing transcript: \"$transcript\"")
        voicePipeline.setThinking()

        serviceScope.launch {
            try {
                val context = contextEngine.getCurrentContext()
                val result = brainRouter.route(transcript, context)

                // Execute action if present
                if (result.actionJson != null) {
                    val actionResult = try {
                        actionExecutor.execute(result.actionJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Action execution failed", e)
                        com.aetheria.cipher.actions.ActionExecutor.ActionResult(
                            success = false,
                            output = e.message ?: "Unknown error",
                            spokenResponse = "Sorry, couldn't complete that action."
                        )
                    }
                    voicePipeline.speak(actionResult.spokenResponse)
                } else {
                    voicePipeline.speak(result.spokenResponse)
                }

            } catch (e: Exception) {
                Log.e(TAG, "BrainRouter processing failed", e)
                voicePipeline.speak("Sorry, something went wrong. Please try again.")
            }
        }
    }

    // ── Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cipher Core Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Always-on Cipher agent service" }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun startForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cipher")
            .setContentText("Agent active — listening")
            .setSmallIcon(R.drawable.cipher_orb)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
