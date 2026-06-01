package com.aetheria.vance.voice

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
import java.util.LinkedList

/**
 * Foreground service that listens for the wake word using openWakeWord.
 *
 * openWakeWord is fully open-source, runs entirely on-device, and requires no API key.
 * Default wake word: "hey_jarvis" (closest available model to "Hey Cipher").
 *
 * Pipeline: Audio → Mel Spectrogram (TFLite) → Embedding Model (TFLite) →
 *           Classification Model (TFLite) → score vs threshold
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

        private const val MODEL_DIR = "openwakeword"
        private const val MODEL_CLASSIFY = "$MODEL_DIR/hey_jarvis_v0.1.tflite"
        private const val MODEL_EMBED = "$MODEL_DIR/embedding_model.tflite"
        private const val MODEL_MEL = "$MODEL_DIR/melspectrogram.tflite"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
        private const val DEFAULT_SENSITIVITY = 0.5f

        // openWakeWord audio parameters
        private const val CHUNK_SIZE_MS = 16       // ms per inference step
        private const val CHUNK_SIZE = SAMPLE_RATE * CHUNK_SIZE_MS / 1000  // 256 samples
        private const val MEL_WINDOW_SIZE_MS = 100 // ms of audio for mel spectrogram
        private const val MEL_WINDOW_SIZE = SAMPLE_RATE * MEL_WINDOW_SIZE_MS / 1000  // 1600 samples
        private const val STRIDE_MS = 20           // stride between mel windows
        private const val STRIDE_SAMPLES = SAMPLE_RATE * STRIDE_MS / 1000  // 320 samples
        private const val EMBEDDING_HISTORY_SIZE = 3  // number of embeddings to accumulate
    }

    private var melInterpreter: Interpreter? = null
    private var embedInterpreter: Interpreter? = null
    private var classifyInterpreter: Interpreter? = null
    private var isRunning = false
    private var sensitivity = DEFAULT_SENSITIVITY

    // Rolling audio buffer for mel spectrogram computation
    private val audioBuffer = LinkedList<Short>()

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
            CHANNEL_ID, "Cipher Wake Word",
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
            melInterpreter = Interpreter(loadModelFile(MODEL_MEL), interpOptions())
            embedInterpreter = Interpreter(loadModelFile(MODEL_EMBED), interpOptions())
            classifyInterpreter = Interpreter(loadModelFile(MODEL_CLASSIFY), interpOptions())
            Log.d(TAG, "openWakeWord initialized — 3 models loaded (sensitivity=$sensitivity)")
        } catch (e: Exception) {
            Log.e(TAG, "openWakeWord initialization failed: ${e.message}", e)
        }
    }

    private fun loadModelFile(path: String): MappedByteBuffer {
        val fd = assets.openFd(path)
        val stream = FileInputStream(fd.fileDescriptor)
        return stream.channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
    }

    private fun interpOptions() = Interpreter.Options().apply {
        setNumThreads(1)
        setUseNNAPI(false)
    }

    // ── Audio capture & inference loop ─────────────────────────────

    private fun startListening() {
        if (melInterpreter == null || embedInterpreter == null || classifyInterpreter == null) {
            Log.w(TAG, "Models not initialized — cannot start listening")
            return
        }
        isRunning = true
        Log.d(TAG, "Wake word listener started (threshold=${getThreshold()})")

        Thread {
            try {
                val audioRecord = createAudioRecord() ?: run {
                    Log.e(TAG, "Failed to create AudioRecord")
                    isRunning = false
                    return@Thread
                }
                audioRecord.startRecording()
                val buffer = ShortArray(CHUNK_SIZE)
                val embeddingHistory = LinkedList<FloatArray>()

                while (isRunning) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    // Add samples to rolling buffer
                    for (i in 0 until read) {
                        audioBuffer.add(buffer[i])
                    }
                    // Trim buffer to max needed size
                    while (audioBuffer.size > MEL_WINDOW_SIZE + STRIDE_SAMPLES) {
                        audioBuffer.removeFirst()
                    }

                    // Only process if we have enough audio
                    if (audioBuffer.size < MEL_WINDOW_SIZE) continue

                    // Convert buffer to float array for mel model
                    val audioFloat = audioBuffer.take(MEL_WINDOW_SIZE).map { it / 32768.0f }.toFloatArray()

                    // Step 1: Compute mel spectrogram
                    val melArray = computeMel(audioFloat)

                    // Step 2: Compute embedding from mel spectrogram
                    val embedding = computeEmbedding(melArray) ?: continue

                    // Step 3: Accumulate embeddings and run classification
                    embeddingHistory.add(embedding)
                    while (embeddingHistory.size > EMBEDDING_HISTORY_SIZE) {
                        embeddingHistory.removeFirst()
                    }

                    if (embeddingHistory.size >= EMBEDDING_HISTORY_SIZE) {
                        val score = classify(embeddingHistory)
                        if (score >= getThreshold()) {
                            onWakeWordDetected(score)
                            // Clear history to prevent rapid re-triggers
                            embeddingHistory.clear()
                        }
                    }
                }

                audioRecord.stop()
                audioRecord.release()
                Log.d(TAG, "AudioRecord stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
                isRunning = false
            }
        }.start()
    }

    // ── Mel spectrogram computation via TFLite model ──────────────

    private fun computeMel(audioData: FloatArray): FloatArray {
        val inputBuffer = ByteBuffer.allocateDirect(audioData.size * 4).apply {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().put(audioData)
        }
        val outputSize = melInterpreter!!.getOutputTensor(0).shape()[0]
        val output = Array(1) { FloatArray(outputSize) }
        melInterpreter!!.run(inputBuffer, output)
        return output[0]
    }

    // ── Embedding computation via TFLite model ────────────────────

    private fun computeEmbedding(melData: FloatArray): FloatArray? {
        return try {
            val inputBuffer = ByteBuffer.allocateDirect(melData.size * 4).apply {
                order(ByteOrder.nativeOrder())
                asFloatBuffer().put(melData)
            }
            val outputSize = embedInterpreter!!.getOutputTensor(0).shape()[0]
            val output = Array(1) { FloatArray(outputSize) }
            embedInterpreter!!.run(inputBuffer, output)
            output[0]
        } catch (e: Exception) {
            Log.e(TAG, "Embedding computation failed", e)
            null
        }
    }

    // ── Classification via TFLite model ───────────────────────────

    private fun classify(embeddingHistory: List<FloatArray>): Float {
        // Concatenate embeddings into a single input
        val totalSize = embeddingHistory.sumOf { it.size }
        val inputBuffer = ByteBuffer.allocateDirect(totalSize * 4).apply {
            order(ByteOrder.nativeOrder())
            for (emb in embeddingHistory) {
                asFloatBuffer().put(emb)
            }
        }
        val output = Array(1) { FloatArray(1) }
        classifyInterpreter!!.run(inputBuffer, output)
        return output[0][0]
    }

    private fun stopListening() {
        isRunning = false
        try { melInterpreter?.close() } catch (_: Exception) {}
        try { embedInterpreter?.close() } catch (_: Exception) {}
        try { classifyInterpreter?.close() } catch (_: Exception) {}
        melInterpreter = null
        embedInterpreter = null
        classifyInterpreter = null
        audioBuffer.clear()
        Log.d(TAG, "Wake word listener stopped")
    }

    private fun createAudioRecord(): android.media.AudioRecord? {
        val minBuf = android.media.AudioRecord.getMinBufferSize(
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
                            .setEncoding(AUDIO_FORMAT).build()
                    )
                    .setBufferSizeInBytes(minBuf.coerceAtLeast(CHUNK_SIZE * 4))
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                    minBuf.coerceAtLeast(CHUNK_SIZE * 4)
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
        } catch (_: Exception) { DEFAULT_SENSITIVITY }
    }

    private fun getThreshold(): Float {
        // openWakeWord scores: higher = more confident it's the wake word
        // Invert sensitivity: high sensitivity = low threshold = easier trigger
        return 1.0f - sensitivity
    }
}
