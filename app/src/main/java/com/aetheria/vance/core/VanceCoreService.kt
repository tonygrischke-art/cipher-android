package com.aetheria.vance.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aetheria.vance.R
import com.aetheria.vance.actions.ActionExecutor
import com.aetheria.vance.brain.BrainRouter
import com.aetheria.vance.context.ContextEngine
import com.aetheria.vance.context.MemoryStore
import com.aetheria.vance.notifications.VanceNotificationListener
import com.aetheria.vance.ui.FloatingOrbService
import com.aetheria.vance.ui.MainActivity
import com.aetheria.vance.voice.VoicePipeline
import com.aetheria.vance.voice.WakeWordService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VanceCoreService : Service() {

    companion object {
        private const val TAG = "CipherCore"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cipher_core_channel"

        const val ACTION_WAKE_WORD_DETECTED = "ACTION_WAKE_WORD_DETECTED"
        const val ACTION_PROCESS_TRANSCRIPT = "ACTION_PROCESS_TRANSCRIPT"
        const val ACTION_NOTIFICATION_RECEIVED = "com.aetheria.vance.NOTIFICATION_RECEIVED"
        const val ACTION_FOREGROUND_APP_CHANGED = "com.aetheria.vance.FOREGROUND_APP_CHANGED"
    }

    @Inject lateinit var brainRouter: BrainRouter
    @Inject lateinit var contextEngine: ContextEngine
    @Inject lateinit var voicePipeline: VoicePipeline
    @Inject lateinit var actionExecutor: ActionExecutor
    @Inject lateinit var memoryStore: MemoryStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var powerManager: PowerManager
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var isThermallyThrottled = false
    private val deferredTranscripts = java.util.concurrent.ConcurrentLinkedQueue<String>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VanceCoreService created")
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registerThermalListener()
        }
        createNotificationChannel()
        startForeground()
        initializeSubsystems()
        registerNotificationReceiver()
        registerAppChangeReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_WAKE_WORD_DETECTED -> handleWakeWord(intent)
            ACTION_PROCESS_TRANSCRIPT -> {
                val transcript = intent.getStringExtra("transcript") ?: ""
                if (transcript.isNotBlank()) handleTranscript(transcript)
            }
            ACTION_NOTIFICATION_RECEIVED -> handleNotification(intent)
            ACTION_FOREGROUND_APP_CHANGED -> handleAppChange(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "VanceCoreService destroying")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { powerManager.removeThermalStatusListener(it) }
        }
        voicePipeline.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Thermal management ───────────────────────────────────────

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermalListener() {
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            when {
                status >= PowerManager.THERMAL_STATUS_SEVERE -> {
                    Log.e(TAG, "Thermal SEVERE — pausing wake word")
                    isThermallyThrottled = true
                    deferredTranscripts.clear()
                    sendBroadcast(Intent("com.aetheria.vance.THERMAL_PAUSE").apply {
                        setPackage(packageName)
                    })
                }
                status >= PowerManager.THERMAL_STATUS_MODERATE -> {
                    Log.w(TAG, "Thermal MODERATE — throttling")
                    isThermallyThrottled = true
                }
                else -> {
                    Log.i(TAG, "Thermal normal — resuming")
                    isThermallyThrottled = false
                    drainDeferredTranscripts()
                }
            }
        }.also { powerManager.addThermalStatusListener(it) }
    }

    private fun drainDeferredTranscripts() {
        var transcript = deferredTranscripts.poll()
        while (transcript != null) {
            handleTranscript(transcript)
            transcript = deferredTranscripts.poll()
        }
    }

    // ── Initialization ─────────────────────────────────────────────

    private fun initializeSubsystems() {
        Log.d(TAG, "Initializing subsystems")
        voicePipeline.initialize()
        // WakeWordService is started by OnboardingActivity's LaunchedEffect.
        // Don't start it again here to avoid duplicate service instances.

        // Check Groq API key — warn if cloud fallback is disabled
        try {
            val prefs = getSharedPreferences("cipher_secure_prefs", Context.MODE_PRIVATE)
            val groqKey = prefs.getString("groq_api_key", "") ?: ""
            if (groqKey.isBlank()) {
                Log.w(TAG, "Groq API key not set — cloud fallback disabled. Set key in Settings.")
            } else {
                Log.d(TAG, "Groq API key is set — cloud fallback available")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check Groq key: ${e.message}")
        }

        // Start FloatingOrbService if overlay permission is granted
        if (Settings.canDrawOverlays(this)) {
            val orbIntent = Intent(this, FloatingOrbService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(orbIntent)
            } else {
                startService(orbIntent)
            }
            Log.d(TAG, "FloatingOrbService started")
        } else {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — FloatingOrbService not started")
        }
    }

    private fun registerNotificationReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                notificationReceiver,
                IntentFilter(ACTION_NOTIFICATION_RECEIVED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UNSPECIFIED_REGISTER_RECEIVER_FLAG")
            registerReceiver(
                notificationReceiver,
                IntentFilter(ACTION_NOTIFICATION_RECEIVED)
            )
        }
    }

    private fun registerAppChangeReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                appChangeReceiver,
                IntentFilter(ACTION_FOREGROUND_APP_CHANGED),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UNSPECIFIED_REGISTER_RECEIVER_FLAG")
            registerReceiver(
                appChangeReceiver,
                IntentFilter(ACTION_FOREGROUND_APP_CHANGED)
            )
        }
    }

    // ── Receivers ───────────────────────────────────────────────────

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val title = intent?.getStringExtra("title") ?: ""
            val text = intent?.getStringExtra("text") ?: ""
            val packageName = intent?.getStringExtra("package_name") ?: ""
            Log.d(TAG, "Notification received: [$packageName] $title: $text")
            // Update context engine
            if (title.isNotBlank() || text.isNotBlank()) {
                contextEngine.lastNotification = "$title: $text".take(100)
            }
        }
    }

    private val appChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val packageName = intent?.getStringExtra("package_name") ?: return
            Log.d(TAG, "App changed: $packageName")
            contextEngine.foregroundAppPackage = packageName
        }
    }

    // ── Intent handlers ────────────────────────────────────────────

    private fun handleWakeWord(intent: Intent) {
        val wakeWord = intent.getStringExtra("wake_word") ?: "unknown"
        Log.d(TAG, "Wake word detected: $wakeWord")

        // Upgrade FGS to include microphone type now that we actually need the mic
        enableMicrophoneFgs()

        voicePipeline.startListening(
            onTranscript = { transcript ->
                handleTranscript(transcript)
            },
            onTimeout = {
                Log.d(TAG, "Listening timeout — returning to idle")
            }
        )
    }

    private fun handleNotification(intent: Intent) {
        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        if (title.isNotBlank() || text.isNotBlank()) {
            contextEngine.lastNotification = "$title: $text".take(100)
        }
    }

    private fun handleAppChange(intent: Intent) {
        val packageName = intent.getStringExtra("package_name") ?: return
        contextEngine.foregroundAppPackage = packageName
    }

    private fun handleTranscript(transcript: String) {
        if (isThermallyThrottled) {
            Log.w(TAG, "Thermally throttled — deferring: \"$transcript\"")
            deferredTranscripts.offer(transcript)
            return
        }

        Log.d(TAG, "Processing transcript: \"$transcript\"")
        voicePipeline.setThinking()

        serviceScope.launch {
            try {
                // Get context snapshot
                val contextSnapshot = try {
                    contextEngine.getSnapshot()
                } catch (e: Exception) {
                    Log.w(TAG, "Context snapshot failed, using cached", e)
                    null
                }
                val contextStr = contextSnapshot?.let {
                    contextEngine.formatForPrompt(it)
                } ?: contextEngine.getCurrentContext()

                // Load conversation history for context
                val recentHistory = try {
                    memoryStore.getRecentConversations(5)
                        .joinToString("\n") { "${it.role}: ${it.content}" }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load conversation history", e)
                    ""
                }

                val result = brainRouter.route(transcript, contextStr, recentHistory)

                // Execute action if present
                val responseText = if (result.actionJson != null) {
                    val actionResult = try {
                        actionExecutor.execute(result.actionJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Action execution failed", e)
                        ActionExecutor.ActionResult(
                            success = false,
                            output = e.message ?: "Unknown error",
                            spokenResponse = "Sorry, couldn't complete that action."
                        )
                    }
                    actionResult.spokenResponse
                } else {
                    result.spokenResponse
                }

                // Save to memory
                try {
                    val sessionId = "default"
                    memoryStore.saveExchange(sessionId, transcript, responseText, result.actionJson)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save to memory", e)
                }

                voicePipeline.speak(responseText)

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

    private fun startForeground(withMicrophone: Boolean = false) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cipher")
            .setContentText(if (withMicrophone) "Agent active — listening" else "Agent active — idle")
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

    /** Call when actually starting mic capture (wake word detected / user prompt). */
    fun enableMicrophoneFgs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(withMicrophone = true)
        }
    }
}
