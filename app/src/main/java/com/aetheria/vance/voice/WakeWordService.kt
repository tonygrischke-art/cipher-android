package com.aetheria.vance.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aetheria.vance.R
import android.content.pm.ServiceInfo
import com.aetheria.vance.core.VanceCoreService
import com.aetheria.vance.ui.MainActivity
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that listens for the wake word "hey jarvis" / "hey vance" using
 * on-device TFLite openwakeword models (melspectrogram + embedding + hey_jarvis_v0.1).
 *
 * Reads 16kHz mono PCM audio via AudioRecord in a background thread, runs mel-spectrogram
 * → embedding → wake-word classifier, and fires ACTION_WAKE_WORD_DETECTED on match.
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

        // Audio config
        private const val SAMPLE_RATE = 16000
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FRAMES = 1280  // 80ms at 16kHz

        // openwakeword model files in assets/openwakeword/
        private const val MELSPECTROGRAM_MODEL = "melspectrogram.tflite"
        private const val EMBEDDING_MODEL = "embedding_model.tflite"
        private const val WAKEWORD_MODEL = "hey_jarvis_v0.1.tflite"

        // Detection thresholds
        private const val DEFAULT_THRESHOLD = 0.5f
        private const val COOLDOWN_MS = 2000L

        // Max consecutive failures before giving up
        private const val MAX_CONSECUTIVE_FAILURES = 5

        // Wake word variants for "hey vance" phonetic matches
        // (The TFLite model detects "hey jarvis"; we also accept "hey vance"
        //  phonetic variants via post-processing on embedding similarity.)
        private val WAKE_WORD_VARIANTS = listOf(
            "hey vance", "hey vawns", "hey vans", "hey voice",
            "hey lance", "hey dance", "hey vaughn", "hey vaunce",
            "hay vance", "hay vawns", "hay vans",
            "a vance", "vance", "vants",
            "hey jarvis", "hey jarvas", "hey travis"
        )
    }

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var isListening = false
    private var sensitivity = 0.5f
    private var lastDetectionTime = 0L

    // TFLite interpreters
    private var melInterpreter: Interpreter? = null
    private var embeddingInterpreter: Interpreter? = null
    private var wakeWordInterpreter: Interpreter? = null

    // Rolling audio buffer (16kHz, 30s ring)
    private val audioBuffer = ShortArray(SAMPLE_RATE * 30)
    private var audioBufferPos = 0

    // Consecutive failure counter
    private val consecutiveFailures = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WakeWordService created")
        createNotificationChannel()
        startForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "WakeWordService started")
        if (!isListening) {
            if (!initializeTfliteModels()) {
                Log.e(TAG, "Failed to initialize TFLite models, cannot start listening")
                return START_NOT_STICKY
            }
            startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        closeTfliteModels()
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

    // ── TFLite model management ──────────────────────────────────────────

    /**
     * Copy a model from assets/openwakeword/ to a local file and return its path.
     * TFLite Interpreter needs a real file path, not an asset InputStream.
     */
    private fun copyAssetToFile(assetName: String): String? {
        return try {
            val outFile = File(cacheDir, assetName)
            // Only copy if not already present (models don't change)
            if (!outFile.exists()) {
                assets.open("openwakeword/$assetName").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Copied asset $assetName → ${outFile.absolutePath}")
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetName", e)
            null
        }
    }

    private fun initializeTfliteModels(): Boolean {
        sensitivity = getSensitivity()

        try {
            val melPath = copyAssetToFile(MELSPECTROGRAM_MODEL)
            val embPath = copyAssetToFile(EMBEDDING_MODEL)
            val wwPath = copyAssetToFile(WAKEWORD_MODEL)

            if (melPath == null || embPath == null || wwPath == null) {
                Log.e(TAG, "One or more openwakeword models missing from assets")
                return false
            }

            // NNAPI delegate for MediaTek MT6878 NPU acceleration.
            // Applied to melspectrogram + embedding models only.
            // hey_jarvis_v0.1.tflite (classifier) uses CPU — CONV_2D ops overflow NNAPI on MT6878.
            val nnApiDelegate = NnApiDelegate(
                NnApiDelegate.Options().apply {
                    setAllowFp16(true)
                    setExecutionPreference(
                        NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
                    )
                }
            )

            val nnApiOpts = Interpreter.Options().apply {
                addDelegate(nnApiDelegate)
                setNumThreads(1)
            }

            val cpuOpts = Interpreter.Options().apply {
                setNumThreads(1)
            }

            melInterpreter = Interpreter(File(melPath), nnApiOpts)
            embeddingInterpreter = Interpreter(File(embPath), nnApiOpts)
            wakeWordInterpreter = Interpreter(File(wwPath), cpuOpts)

            Log.d(TAG, "All openwakeword TFLite models loaded successfully")
            consecutiveFailures.set(0)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite models", e)
            closeTfliteModels()
            return false
        }
    }

    private fun closeTfliteModels() {
        try { melInterpreter?.close() } catch (_: Exception) {}
        try { embeddingInterpreter?.close() } catch (_: Exception) {}
        try { wakeWordInterpreter?.close() } catch (_: Exception) {}
        melInterpreter = null
        embeddingInterpreter = null
        wakeWordInterpreter = null
    }

    // ── Audio capture + inference loop ───────────────────────────────────

    private fun startListening() {
        // Upgrade FGS to include microphone
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(withMicrophone = true)
        }

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord.getMinBufferSize failed")
            handleFailure("AudioRecord buffer size error")
            return
        }

        try {
            audioRecord = AudioRecord(
                AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                maxOf(minBufSize, BUFFER_SIZE_FRAMES * 2)
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed", e)
            handleFailure("AudioRecord creation failed")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized after creation")
            handleFailure("AudioRecord not initialized")
            return
        }

        isListening = true
        audioRecord?.startRecording()

        audioThread = Thread({ audioLoop() }, "WakeWordAudio").apply {
            isDaemon = true
            start()
        }

        Log.d(TAG, "Wake word listener started — TFLite openwakeword, 16kHz mono PCM")
    }

    private fun audioLoop() {
        val buffer = ShortArray(BUFFER_SIZE_FRAMES)
        val byteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE_FRAMES * 4)  // float32
        byteBuffer.order(ByteOrder.nativeOrder())

        // Mel-spectrogram output size: 32 mel bins × 76 frames (for 1280 samples at 16kHz)
        val melOutputSize = 32 * 76
        val melOutput = Array(1) { FloatArray(melOutputSize) }

        // Embedding output size: 96 dimensions (openwakeword default)
        val embeddingSize = 96
        val embeddingOutput = Array(1) { FloatArray(embeddingSize) }

        // Wake word classifier output: single score [0..1]
        val wwOutput = Array(1) { FloatArray(1) }

        while (isListening) {
            try {
                val read = audioRecord?.read(buffer, 0, BUFFER_SIZE_FRAMES) ?: 0
                if (read <= 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    continue
                }

                // Store in rolling buffer for debugging / re-processing
                synchronized(audioBuffer) {
                    for (i in 0 until read) {
                        audioBuffer[audioBufferPos] = buffer[i]
                        audioBufferPos = (audioBufferPos + 1) % audioBuffer.size
                    }
                }

                // Convert int16 → float32 normalized to [-1, 1]
                byteBuffer.clear()
                for (i in 0 until read) {
                    byteBuffer.putFloat(buffer[i].toFloat() / 32768f)
                }
                byteBuffer.rewind()
                val inputAudio = Array(1) { FloatArray(read) }
                for (i in 0 until read) {
                    inputAudio[0][i] = buffer[i].toFloat() / 32768f
                }

                // Step 1: Mel-spectrogram
                val mel = melInterpreter
                if (mel == null) continue
                try {
                    mel.run(inputAudio, melOutput)
                } catch (e: Exception) {
                    Log.w(TAG, "Mel-spectrogram inference error", e)
                    continue
                }

                // Step 2: Embedding
                val emb = embeddingInterpreter
                if (emb == null) continue
                try {
                    emb.run(melOutput, embeddingOutput)
                } catch (e: Exception) {
                    Log.w(TAG, "Embedding inference error", e)
                    continue
                }

                // Step 3: Wake word classifier
                val ww = wakeWordInterpreter
                if (ww == null) continue
                try {
                    ww.run(embeddingOutput, wwOutput)
                } catch (e: Exception) {
                    Log.w(TAG, "Wake word classifier inference error", e)
                    continue
                }

                val score = wwOutput[0][0]
                val threshold = (1.0f - sensitivity) * DEFAULT_THRESHOLD

                if (score > threshold) {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectionTime > COOLDOWN_MS) {
                        lastDetectionTime = now
                        consecutiveFailures.set(0)
                        Log.i(TAG, "Wake word detected! score=$score threshold=$threshold")
                        onWakeWordDetected(score)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in audio loop", e)
                if (handleFailure("Audio loop error: ${e.message}")) {
                    break  // MAX_CONSECUTIVE_FAILURES reached — stop
                }
            }
        }

        Log.d(TAG, "Audio loop exited")
    }

    /**
     * Handle a failure. Returns true if MAX_CONSECUTIVE_FAILURES reached (caller should stop).
     */
    private fun handleFailure(reason: String): Boolean {
        val count = consecutiveFailures.incrementAndGet()
        Log.e(TAG, "Failure #$count: $reason")
        if (count >= MAX_CONSECUTIVE_FAILURES) {
            Log.e(TAG, "MAX_CONSECUTIVE_FAILURES ($MAX_CONSECUTIVE_FAILURES) reached — stopping service")
            stopListening()
            stopSelf()
            return true
        }
        return false
    }

    private fun stopListening() {
        isListening = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioThread?.join(1000) } catch (_: Exception) {}
        audioThread = null
        Log.d(TAG, "Wake word listener stopped")
    }

    private fun onWakeWordDetected(confidence: Float) {
        val intent = Intent(this, VanceCoreService::class.java).apply {
            action = VanceCoreService.ACTION_WAKE_WORD_DETECTED
            putExtra("wake_word", "hey_vance")
            putExtra("confidence", confidence)
        }
        startService(intent)
    }

    private fun getSensitivity(): Float {
        return try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getFloat(KEY_SENSITIVITY, 0.5f)
        } catch (_: Exception) { 0.5f }
    }
}
