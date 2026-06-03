package com.aetheria.vance.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aetheria.vance.R
import android.content.pm.ServiceInfo
import com.aetheria.vance.core.VanceCoreService
import com.aetheria.vance.ui.MainActivity
import java.util.Locale

/**
 * Foreground service that listens for the wake word "hey cipher" using
 * Android's built-in SpeechRecognizer (on-device if available).
 *
 * Uses RecognitionListener to continuously listen for speech and checks
 * each partial/full result against wake word phrases. On match, fires
 * ACTION_WAKE_WORD_DETECTED to trigger Vance response.
 *
 * No TFLite models needed — avoids MT6878 CONV_2D overflow issue.
 *
 * Includes max-restart counter: stops after 5 consecutive failures.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "cipher_wake_channel"
        private const val PREFS_NAME = "cipher_secure_prefs"
        private const val KEY_SENSITIVITY = "wake_word_sensitivity"

        // Detection
        private const val COOLDOWN_MS = 2000L
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val SILENCE_RESTART_MS = 3000L  // restart listen after 3s of no speech

        // Wake word phrases to detect (lowercased)
        private val WAKE_PHRASES = listOf(
            "hey cipher", "hey cypher",
            "hi cipher", "hi cypher",
            "cipher", "cypher",
            "hey vance", "hi vance", "vance"
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var lastDetectionTime = 0L
    private var consecutiveFailures = 0
    private val handler = Handler(Looper.getMainLooper())
    private var restartPending = false
    private var sensitivity = 0.5f

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WakeWordService created")
        createNotificationChannel()
        startForeground(withMicrophone = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WakeWordService started")
        if (!isListening) {
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Cipher Wake Word",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Wake word detection service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForeground(withMicrophone: Boolean = false) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vance")
            .setContentText(
                if (withMicrophone) "Listening — say \"Hey Cipher\""
                else "Vance standby — say \"Hey Cipher\""
            )
            .setSmallIcon(R.drawable.cipher_orb)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withMicrophone) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Speech-based wake word detection ────────────────────────────────

    private fun startListening() {
        sensitivity = getSensitivity()

        // Create SpeechRecognizer on main thread (required)
        handler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    Log.e(TAG, "SpeechRecognizer not available on this device")
                    handleFailure("SpeechRecognizer unavailable")
                    return@post
                }

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(createRecognitionListener())
                }

                startListeningSession()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SpeechRecognizer", e)
                handleFailure("SpeechRecognizer creation failed: ${e.message}")
            }
        }
    }

    private fun startListeningSession() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)  // get partial transcripts
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            Log.d(TAG, "Listening session started — say \"Hey Cipher\"")
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            handleFailure("startListening failed: ${e.message}")
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                isListening = true
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech beginning")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended — scheduling restart")
                scheduleRestart()
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no mic permission"
                    SpeechRecognizer.ERROR_NETWORK -> "network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "no match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                    SpeechRecognizer.ERROR_SERVER -> "server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
                    else -> "unknown ($error)"
                }
                Log.w(TAG, "Recognition error: $msg")

                // Recoverable errors: voice_code=1,2,3,5,6,8 (no match, server, speech timeout)
                // Non-recoverable: 4 (insufficient_permissions)
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    handleFailure("Mic permission denied")
                } else {
                    // These happen at end of session — just restart
                    isListening = false
                    scheduleRestart()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (match in matches) {
                        Log.d(TAG, "Result: \"$match\"")
                        checkWakePhrase(match)
                    }
                }
                isListening = false
                scheduleRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (match in matches) {
                        Log.d(TAG, "Partial: \"$match\"")
                        checkWakePhrase(match)
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun checkWakePhrase(transcript: String) {
        val lower = transcript.lowercase(Locale.US).trim()
        for (phrase in WAKE_PHRASES) {
            if (lower.contains(phrase)) {
                val now = System.currentTimeMillis()
                if (now - lastDetectionTime > COOLDOWN_MS) {
                    lastDetectionTime = now
                    consecutiveFailures = 0
                    Log.i(TAG, "WAKE WORD DETECTED! \"$transcript\" → matched \"$phrase\"")
                    onWakeWordDetected(phrase)
                }
                return
            }
        }
    }

    private fun scheduleRestart() {
        if (restartPending) return
        restartPending = true
        handler.postDelayed({
            restartPending = false
            if (isServiceRunning()) {
                Log.d(TAG, "Restarting listening session")
                try {
                    startListeningSession()
                } catch (e: Exception) {
                    Log.e(TAG, "Restart failed", e)
                    handleFailure("Restart failed: ${e.message}")
                }
            }
        }, 200L)  // brief pause before restart
    }

    private fun isServiceRunning(): Boolean {
        return speechRecognizer != null
    }

    private fun handleFailure(reason: String): Boolean {
        consecutiveFailures++
        Log.e(TAG, "Failure #$consecutiveFailures: $reason")
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "MAX_CONSECUTIVE_FAILURES reached — stopping service")
            stopListening()
            stopSelf()
            return true
        }
        return false
    }

    private fun stopListening() {
        isListening = false
        handler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        Log.d(TAG, "Listener stopped and SR destroyed")
    }

    private fun onWakeWordDetected(phrase: String) {
        sendBroadcast(Intent(VanceCoreService.ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
            putExtra("wake_word", phrase)
        })
    }

    private fun getSensitivity(): Float {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getFloat(KEY_SENSITIVITY, 0.5f)
        } catch (_: Exception) { 0.5f }
    }
}
