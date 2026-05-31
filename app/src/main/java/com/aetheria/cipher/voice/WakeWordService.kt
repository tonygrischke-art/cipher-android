package com.aetheria.cipher.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import androidx.core.app.NotificationCompat
import com.aetheria.cipher.R
import com.aetheria.cipher.core.CipherCoreService
import com.aetheria.cipher.ui.MainActivity

/**
 * Foreground service that listens for the wake word using Picovoice Porcupine.
 *
 * Keyword: "hey cipher" (or "jarvis" as fallback if hey-cipher model is not available).
 * Access key: read from encrypted SharedPreferences under key "porcupine_access_key".
 *
 * On detection → sends ACTION_WAKE_WORD_DETECTED to CipherCoreService.
 * If no access key configured → logs warning, wake word disabled, manual trigger only.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "cipher_wake_channel"
        private const val PREFS_NAME = "cipher_secure_prefs"
        private const val KEY_ACCESS_KEY = "porcupine_access_key"

        /** Porcupine built-in keyword to use. */
        private const val KEYWORD = Porcupine.BuiltInKeyword.JARVIS
    }

    private var porcupine: Porcupine? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WakeWordService created")
        createNotificationChannel()
        startForeground()
        initializePorcupine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WakeWordService started")
        if (!isRunning) {
            startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
        Log.d(TAG, "WakeWordService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cipher Wake Word",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Wake word detection service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cipher")
            .setContentText("Wake word listener active")
            .setSmallIcon(R.drawable.cipher_orb)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Porcupine initialization ───────────────────────────────────

    private fun initializePorcupine() {
        val accessKey = getAccessKey()
        if (accessKey.isNullOrBlank()) {
            Log.w(TAG, "No Porcupine access key found in encrypted prefs. Wake word disabled — use manual trigger.")
            return
        }

        try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeyword(KEYWORD)
                .build(applicationContext)

            Log.d(TAG, "Porcupine initialized successfully (keyword: $KEYWORD, frameLength=${porcupine?.frameLength}, sampleRate=${porcupine?.sampleRate})")
            startListening()
        } catch (e: PorcupineException) {
            Log.e(TAG, "Porcupine initialization failed: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error initializing Porcupine: ${e.message}", e)
        }
    }

    /**
     * Start the audio recording and frame-processing loop.
     * Porcupine processes audio frames via its native engine.
     */
    private fun startListening() {
        val engine = porcupine ?: run {
            Log.w(TAG, "Porcupine not initialized — cannot start listening")
            return
        }

        isRunning = true
        Log.d(TAG, "Wake word listener started")

        // Porcupine's Android SDK handles audio capture internally when built.
        // We start a background thread to read frames from AudioRecord.
        Thread {
            try {
                val audioRecord = createAudioRecord(engine.sampleRate, engine.frameLength)
                if (audioRecord == null) {
                    Log.e(TAG, "Failed to create AudioRecord for Porcupine")
                    isRunning = false
                    return@Thread
                }

                audioRecord.startRecording()
                val buffer = ShortArray(engine.frameLength)

                while (isRunning) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read == buffer.size) {
                        try {
                            val keywordIndex = engine.process(buffer)
                            if (keywordIndex >= 0) {
                                onWakeWordDetected(keywordIndex)
                            }
                        } catch (e: PorcupineException) {
                            Log.e(TAG, "Porcupine process error: ${e.message}")
                        }
                    }
                }

                audioRecord.stop()
                audioRecord.release()
                Log.d(TAG, "AudioRecord stopped and released")

            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
                isRunning = false
            }
        }.start()
    }

    private fun stopListening() {
        isRunning = false
        try {
            porcupine?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting Porcupine", e)
        }
        porcupine = null
        Log.d(TAG, "Wake word listener stopped")
    }

    /**
     * Create an AudioRecord instance matching Porcupine's requirements.
     */
    private fun createAudioRecord(sampleRate: Int, frameLength: Int): android.media.AudioRecord? {
        val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.media.AudioRecord.Builder()
                    .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(audioFormat)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize.coerceAtLeast(frameLength * 2 * 2))
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate, channelConfig, audioFormat,
                    minBufferSize.coerceAtLeast(frameLength * 2 * 2)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed: ${e.message}", e)
            null
        }
    }

    // ── Wake word event ────────────────────────────────────────────

    private fun onWakeWordDetected(keywordIndex: Int) {
        Log.d(TAG, "Wake word detected! keywordIndex=$keywordIndex")

        val intent = Intent(this, CipherCoreService::class.java).apply {
            action = CipherCoreService.ACTION_WAKE_WORD_DETECTED
            putExtra("wake_word", "hey_cipher")
        }
        startService(intent)
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun getAccessKey(): String? {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_ACCESS_KEY, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read access key from prefs", e)
            null
        }
    }
}
