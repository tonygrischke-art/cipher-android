package com.aetheria.vance.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aetheria.vance.R
import com.aetheria.vance.core.VanceCoreService
import com.aetheria.vance.ui.MainActivity
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Foreground service that listens for the wake word using openWakeWord.
 *
 * openWakeWord is fully open-source, runs on-device, and requires no API key.
 * Default wake word: "hey_jarvis" (closest available model to "Hey Cipher").
 *
 * On detection → sends ACTION_WAKE_WORD_DETECTED to VanceCoreService.
 * Sensitivity is configurable via SharedPreferences key "wake_word_sensitivity".
 */
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "cipher_wake_channel"
        private const val PREFS_NAME = "cipher_secure_prefs"
        private const val KEY_SENSITIVITY = "wake_word_sensitivity"

        /** openWakeWord model file name in assets. */
        private const val MODEL_FILE = "openwakeword/hey_jarvis_v0.1.tflite"

        /** Audio parameters expected by the model. */
        private const val SAMPLE_RATE = 16000
        private const val FRAME_LENGTH = 1280  // 80ms at 16kHz
        private const val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT

        /** Default sensitivity threshold (0.0 - 1.0). */
        private const val DEFAULT_SENSITIVITY = 0.5f
    }

    private var interpreter: Interpreter? = null
    private var isRunning = false
    private var sensitivity = DEFAULT_SENSITIVITY

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WakeWordService created")
        createNotificationChannel()
        startForeground()
        initializeOpenWakeWord()
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

    // ── openWakeWord initialization ────────────────────────────────

    private fun initializeOpenWakeWord() {
        sensitivity = getSensitivity()
        try {
            val modelBuffer = loadModelFile(MODEL_FILE)
            val options = Interpreter.Options().apply {
                setNumThreads(1)
                setUseNNAPI(false)  // Use CPU for low-latency audio processing
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.d(TAG, "openWakeWord initialized (sensitivity=$sensitivity)")
        } catch (e: Exception) {
            Log.e(TAG, "openWakeWord initialization failed: ${e.message}", e)
            Log.w(TAG, "Wake word disabled — will use manual trigger only")
        }
    }

    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val assetFileDescriptor = assets.openFd(modelPath)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    // ── Audio capture & inference loop ─────────────────────────────

    private fun startListening() {
        val engine = interpreter ?: run {
            Log.w(TAG, "openWakeWord not initialized — cannot start listening")
            return
        }

        isRunning = true
        Log.d(TAG, "Wake word listener started (threshold=${getThreshold()})")

        Thread {
            try {
                val audioRecord = createAudioRecord()
                if (audioRecord == null) {
                    Log.e(TAG, "Failed to create AudioRecord")
                    isRunning = false
                    return@Thread
                }

                audioRecord.startRecording()
                val buffer = ShortArray(FRAME_LENGTH)

                // Rolling buffer for model input (openWakeWord expects
                // a window of audio frames; we accumulate and feed periodically)
                val modelInputSize = engine.getInputTensor(0).shape()[1]
                val audioWindow = FloatArray(modelInputSize)
                var windowPos = 0

                while (isRunning) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read == buffer.size) {
                        // Convert PCM16 to float [-1.0, 1.0]
                        val floatFrame = ShortArray(read) { i ->
                            buffer[i]
                        }.map { it / 32768.0f }.toFloatArray()

                        // Accumulate into window
                        val copyLen = minOf(floatFrame.size, modelInputSize - windowPos)
                        System.arraycopy(floatFrame, 0, audioWindow, windowPos, copyLen)
                        windowPos += copyLen

                        if (windowPos >= modelInputSize) {
                            // Run inference
                            val score = runInference(engine, audioWindow)
                            if (score >= getThreshold()) {
                                onWakeWordDetected(score)
                            }
                            // Shift window by half for overlap
                            val halfShift = modelInputSize / 2
                            System.arraycopy(audioWindow, halfShift, audioWindow, 0, halfShift)
                            windowPos = halfShift
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

    private fun runInference(engine: Interpreter, audioData: FloatArray): Float {
        val inputBuffer = ByteBuffer.allocateDirect(audioData.size * 4).apply {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().put(audioData)
        }

        // openWakeWord model outputs a single confidence score
        val outputBuffer = Array(1) { FloatArray(1) }
        engine.run(inputBuffer, outputBuffer)
        return outputBuffer[0][0]
    }

    private fun stopListening() {
        isRunning = false
        try {
            interpreter?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing openWakeWord interpreter", e)
        }
        interpreter = null
        Log.d(TAG, "Wake word listener stopped")
    }

    private fun createAudioRecord(): android.media.AudioRecord? {
        val minBufferSize = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.media.AudioRecord.Builder()
                    .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .setEncoding(AUDIO_FORMAT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize.coerceAtLeast(FRAME_LENGTH * 2 * 2))
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                    minBufferSize.coerceAtLeast(FRAME_LENGTH * 2 * 2)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed: ${e.message}", e)
            null
        }
    }

    // ── Wake word event ────────────────────────────────────────────

    private fun onWakeWordDetected(confidence: Float) {
        Log.d(TAG, "Wake word detected! confidence=$confidence")

        val intent = Intent(this, VanceCoreService::class.java).apply {
            action = VanceCoreService.ACTION_WAKE_WORD_DETECTED
            putExtra("wake_word", "hey_cipher")
        }
        startService(intent)
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun getSensitivity(): Float {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
        } catch (e: Exception) {
            DEFAULT_SENSITIVITY
        }
    }

    /** Convert sensitivity (0-1) to a threshold the model score must exceed. */
    private fun getThreshold(): Float {
        // openWakeWord scores are typically 0.0-1.0
        // Higher sensitivity = lower threshold = easier to trigger
        return 1.0f - sensitivity
    }
}
