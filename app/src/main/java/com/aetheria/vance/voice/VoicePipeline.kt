package com.aetheria.vance.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

/**
 * Manages the voice interaction loop:
 *   SpeechRecognizer → transcript → BrainRouter → TTS response
 *
 * States: IDLE → LISTENING → THINKING → SPEAKING → IDLE
 *
 * State broadcasts are sent so FloatingOrb can animate:
 *   com.aetheria.vance.STATE_CHANGE with extra "state" = IDLE|LISTENING|THINKING|SPEAKING
 */
class VoicePipeline(private val context: Context) {

    companion object {
        private const val TAG = "VoicePipeline"
        private const val LISTENING_TIMEOUT_MS = 10_000L

        /** Broadcast action for state changes. */
        const val ACTION_STATE_CHANGE = "com.aetheria.vance.VOICE_STATE_CHANGE"
        const val EXTRA_STATE = "state"
    }

    enum class State {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false
    private var isSpeaking = false
    private var isTtsReady = false
    private var isInitialized = false
    private val handler = Handler(Looper.getMainLooper())
    private val recognizerLock = Any()

    private var onTranscriptReady: ((String) -> Unit)? = null
    private var onListeningTimeout: (() -> Unit)? = null
    private var onThinkingComplete: (() -> Unit)? = null
    private var pendingOnSpeakComplete: (() -> Unit)? = null

    private val timeoutRunnable = Runnable {
        Log.w(TAG, "Listening timeout — no speech detected")
        stopListening()
        onListeningTimeout?.invoke()
    }

    // Listener for TTS init
    private val ttsInitListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            isTtsReady = true
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS init failed: $status")
        }
    }

    /**
     * Initialize TTS and SpeechRecognizer. Must be called before use.
     */
    fun initialize() {
        if (isInitialized) return
        Log.d(TAG, "Initializing VoicePipeline")
        initTextToSpeech()
        isInitialized = true
    }

    /**
     * Destroy resources. Call from service destroy.
     */
    fun destroy() {
        handler.removeCallbacks(timeoutRunnable)
        synchronized(recognizerLock) {
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            try { speechRecognizer?.destroy() } catch (_: Exception) {}
            speechRecognizer = null
        }
        try { textToSpeech?.stop(); textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        isInitialized = false
        isTtsReady = false
        Log.d(TAG, "VoicePipeline destroyed")
    }

    /**
     * Start speech recognition. Results delivered via [onTranscript] callback.
     * [onTimeout] called if no speech detected within timeout.
     */
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
        setState(State.LISTENING)

        synchronized(recognizerLock) {
            if (speechRecognizer == null) initSpeechRecognizer()
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
            Log.d(TAG, "Started listening (timeout ${LISTENING_TIMEOUT_MS / 1000}s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            setState(State.IDLE)
            onTranscript("[mic error: ${e.message}]")
        }
    }

    fun stopListening() {
        handler.removeCallbacks(timeoutRunnable)
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        isListening = false
    }

    /**
     * Transition to THINKING state — called while waiting for BrainRouter response.
     */
    fun setThinking() {
        setState(State.THINKING)
    }

    /**
     * Speak [text] via TTS. [onComplete] called when speech finishes.
     * If TTS is not ready, isSpeaking state is still set and onComplete is
     * called after best-effort.
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) {
            setState(State.IDLE)
            onComplete?.invoke()
            return
        }

        if (!isTtsReady || textToSpeech == null) {
            Log.w(TAG, "TTS not ready, re-init'ing")
            initTextToSpeech()
            setState(State.IDLE)
            onComplete?.invoke()
            return
        }

        Log.d(TAG, "Speaking: \"${text.take(80)}${if (text.length > 80) "..." else ""}\"")
        pendingOnSpeakComplete = onComplete
        setState(State.SPEAKING)

        val utteranceId = "cipher_tts_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Already in SPEAKING state
            }

            @Deprecated("Deprecated in API 21, but kept for compatibility")
            override fun onDone(utteranceId: String?) {
                handleSpeakDone()
            }

            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?) {
                handleSpeakError()
            }
        })

        isSpeaking = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            val hashParams = java.util.HashMap<String, String>()
            hashParams[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
            @Suppress("DEPRECATION")
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, hashParams)
        }
    }

    private fun handleSpeakDone() {
        isSpeaking = false
        setState(State.IDLE)
        Log.d(TAG, "TTS done")
        pendingOnSpeakComplete?.invoke()
        pendingOnSpeakComplete = null
    }

    private fun handleSpeakError() {
        isSpeaking = false
        setState(State.IDLE)
        Log.e(TAG, "TTS error")
        pendingOnSpeakComplete?.invoke()
        pendingOnSpeakComplete = null
    }

    fun isSpeaking(): Boolean = isSpeaking
    fun isListening(): Boolean = isListening

    // ── Private ────────────────────────────────────────────────────

    private fun setState(state: State) {
        Log.d(TAG, "State → $state")
        val intent = Intent(ACTION_STATE_CHANGE).apply {
            putExtra(EXTRA_STATE, state.name)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition NOT available on this device")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SR ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech began")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended (waiting for results)")
                isListening = false
                // Don't go to IDLE yet — still waiting for results callback
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no mic permission"
                    SpeechRecognizer.ERROR_NETWORK -> "network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "no speech match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        Log.w(TAG, "SR busy — destroying and resetting")
                        synchronized(recognizerLock) {
                            speechRecognizer?.destroy()
                            speechRecognizer = null
                        }
                        return
                    }
                    SpeechRecognizer.ERROR_SERVER -> "server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech input"
                    else -> "unknown error $error"
                }
                Log.e(TAG, "Speech error $error: $errorMsg")
                isListening = false
                handler.removeCallbacks(timeoutRunnable)
                // Only send error to trigger recovery — don't clear the pipeline
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    onTranscriptReady?.invoke("[error: $errorMsg]")
                } else {
                    onTranscriptReady?.invoke("")
                }
            }

            override fun onResults(results: Bundle?) {
                handler.removeCallbacks(timeoutRunnable)
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull() ?: ""
                Log.d(TAG, "Final transcript: \"$transcript\"")

                if (transcript.isNotBlank()) {
                    setState(State.THINKING)
                } else {
                    setState(State.IDLE)
                }
                onTranscriptReady?.invoke(transcript)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                partial?.firstOrNull()?.let {
                    Log.d(TAG, "Partial: \"$it\"")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        Log.d(TAG, "SpeechRecognizer initialized")
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(context, ttsInitListener)
    }
}
