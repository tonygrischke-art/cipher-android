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
import android.os.IBinder
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
 * Foreground service that listens for the wake word "hey vance" using
 * Android's built-in SpeechRecognizer. This supports arbitrary wake word
 * phrases without needing a custom TFLite model.
 *
 * On detection → sends ACTION_WAKE_WORD_DETECTED to VanceCoreService.
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "cipher_wake_channel"
        private const val PREFS_NAME = "cipher_secure_prefs"
        private const val KEY_SENSITIVITY = "wake_word_sensitivity"

        // Accept multiple spellings/variants of the wake word
        private val WAKE_WORD_VARIANTS = listOf(
            "hey vance", "hey vawns", "hey vans", "hey voice",
            "hey lance", "hey dance", "hey vaughn", "hey vaunce",
            "hay vance", "hay vawns", "hay vans",
            "a vance", "vance", "vants"
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var sensitivity = 0.5f

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WakeWordService created")
        createNotificationChannel()
        startForeground()
        initializeSpeechRecognizer()
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vance")
            .setContentText(
                if (withMicrophone) "Wake word listener active — say \"Hey Vance\""
                else "Vance standby"
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

    private fun initializeSpeechRecognizer() {
        sensitivity = getSensitivity()
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener())
            }
            Log.d(TAG, "SpeechRecognizer initialized")
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer initialization failed: ${e.message}", e)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech beginning")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no permission"
                    SpeechRecognizer.ERROR_NETWORK -> "network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "no match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                    SpeechRecognizer.ERROR_SERVER -> "server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                    else -> "error $error"
                }
                Log.w(TAG, "Speech error: $errorMsg")
                // Restart listening after errors (except no permission)
                if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    restartListening()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                if (matches != null) {
                    for (i in matches.indices) {
                        val text = matches[i].lowercase(Locale.US)
                        val conf = confidences?.getOrNull(i) ?: 0f
                        Log.d(TAG, "Hypothesis: \"$text\" (conf=$conf)")
                        if (isWakeWord(text, conf)) {
                            onWakeWordDetected(conf)
                            break
                        }
                    }
                }
                // Restart listening for continuous detection
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (text in matches) {
                        val lower = text.lowercase(Locale.US)
                        // Quick check on partial results for faster response
                        if (lower.startsWith("hey v") || lower.startsWith("hey va") ||
                            lower.startsWith("hey van") || lower.startsWith("vance")) {
                            Log.d(TAG, "Partial match: \"$lower\"")
                        }
                        if (isWakeWord(lower, 0.5f)) {
                            onWakeWordDetected(0.5f)
                            break
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun isWakeWord(text: String, confidence: Float): Boolean {
        if (confidence < (1.0f - sensitivity)) return false

        // Direct substring match — most reliable
        val lower = text.lowercase(Locale.US).trim()

        // Check for "hey vance" variants
        for (variant in WAKE_WORD_VARIANTS) {
            if (lower.contains(variant)) {
                Log.d(TAG, "Wake word matched variant: \"$variant\" in \"$lower\"")
                return true
            }
        }

        // Fuzzy: check if text starts with "hey v" and has similar ending
        if (lower.startsWith("hey v")) {
            val rest = lower.removePrefix("hey v").trim()
            // "vance", "vawns", "vans", "vants", "vaughn" all share "va" start
            if (rest.startsWith("a") && rest.length in 2..6) {
                Log.d(TAG, "Wake word fuzzy match: \"$lower\"")
                return true
            }
        }

        return false
    }

    private fun startListening() {
        // Upgrade FGS to include microphone — we're about to use the mic
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(withMicrophone = true)
        }

        if (speechRecognizer == null) {
            initializeSpeechRecognizer()
        }
        speechRecognizer?.let { sr ->
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    // Use web-based recognition for better accuracy
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                }
                sr.startListening(intent)
                isListening = true
                Log.d(TAG, "Wake word listener started — listening for 'Hey Vance'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                isListening = false
            }
        }
    }

    private fun restartListening() {
        if (!isListening) return
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        // Small delay to let the recognizer reset
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isListening) {
                try {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    }
                    speechRecognizer?.startListening(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart listening", e)
                }
            }
        }, 300)
    }

    private fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        Log.d(TAG, "Wake word listener stopped")
    }

    private fun onWakeWordDetected(confidence: Float) {
        Log.d(TAG, "Wake word detected! confidence=$confidence")
        // Temporarily stop listening to avoid self-trigger
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}

        val intent = Intent(this, VanceCoreService::class.java).apply {
            action = VanceCoreService.ACTION_WAKE_WORD_DETECTED
            putExtra("wake_word", "hey_vance")
            putExtra("confidence", confidence)
        }
        startService(intent)

        // Resume listening after a delay
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            restartListening()
        }, 2000)
    }

    private fun getSensitivity(): Float {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getFloat(KEY_SENSITIVITY, 0.5f)
        } catch (_: Exception) { 0.5f }
    }
}
