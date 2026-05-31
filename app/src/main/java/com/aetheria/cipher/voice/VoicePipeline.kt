package com.aetheria.cipher.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class VoicePipeline(private val context: Context) {

    companion object {
        private const val TAG = "VoicePipeline"
        private const val LISTENING_TIMEOUT_MS = 10_000L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false
    private var isSpeaking = false
    private val handler = Handler(Looper.getMainLooper())

    private var onTranscriptReady: ((String) -> Unit)? = null
    private var onListeningTimeout: (() -> Unit)? = null

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "Listening timeout")
        stopListening()
        onListeningTimeout?.invoke()
    }

    fun initialize() {
        initSpeechRecognizer()
        initTextToSpeech()
    }

    fun destroy() {
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    fun startListening(
        onTranscript: (String) -> Unit,
        onTimeout: (() -> Unit)? = null
    ) {
        if (isListening) {
            Log.w(TAG, "Already listening, ignoring")
            return
        }

        onTranscriptReady = onTranscript
        onListeningTimeout = onTimeout

        if (speechRecognizer == null) {
            initSpeechRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            handler.postDelayed(timeoutRunnable, LISTENING_TIMEOUT_MS)
            Log.d(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            onTranscript("[mic error]")
        }
    }

    fun stopListening() {
        handler.removeCallbacks(timeoutRunnable)
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        isListening = false
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) return

        if (textToSpeech == null) {
            initTextToSpeech()
        }

        Log.d(TAG, "Speaking: \"${text.take(60)}...\"")

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "cipher_tts")

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                onComplete?.invoke()
            }
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                Log.e(TAG, "TTS error")
            }
        })

        isSpeaking = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "cipher_tts")
        } else {
            @Suppress("DEPRECATION")
            val params = hashMapOf(
                TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to "cipher_tts"
            )
            @Suppress("DEPRECATION")
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params)
        }
    }

    fun isSpeaking(): Boolean = isSpeaking
    fun isListening(): Boolean = isListening

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { Log.d(TAG, "Ready for speech") }
            override fun onBeginningOfSpeech() { Log.d(TAG, "Speech began") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                isListening = false
            }
            override fun onError(error: Int) {
                Log.e(TAG, "Speech error: $error")
                isListening = false
                handler.removeCallbacks(timeoutRunnable)
                onTranscriptReady?.invoke("[error: $error]")
            }
            override fun onResults(results: Bundle?) {
                handler.removeCallbacks(timeoutRunnable)
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull() ?: ""
                Log.d(TAG, "Transcript: \"$transcript\"")
                onTranscriptReady?.invoke(transcript)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                partial?.firstOrNull()?.let { Log.d(TAG, "Partial: \"$it\"") }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.US
                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }
}
