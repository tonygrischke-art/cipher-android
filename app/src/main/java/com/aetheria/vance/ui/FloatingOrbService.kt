package com.aetheria.vance.ui

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.aetheria.vance.R
import com.aetheria.vance.voice.VoicePipeline
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sin

class FloatingOrbService : Service() {

    companion object {
        private const val TAG = "FloatingOrbService"
        const val ACTION_SET_STATE = "com.aetheria.vance.SET_ORB_STATE"
        const val EXTRA_STATE = "state"
    }

    private var windowManager: WindowManager? = null
    private var orbView: OrbCanvasView? = null
    private var stateReceiver: BroadcastReceiver? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    override fun onCreate() {
        super.onCreate()
        // Crash handler — write crash logs to files dir
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val log = java.io.File(filesDir, "cipher_crash_${System.currentTimeMillis()}.txt")
                log.writeText("FloatingOrb thread: ${thread.name}\n${throwable.stackTraceToString()}")
                Log.e(TAG, "Uncaught in FloatingOrb (${thread.name}): ${throwable.message}")
            } catch (_: Exception) {}
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Fix 3: Start as foreground service to prevent Android 10+ crash
        startForeground()
        createOrb()
        registerStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_STATE)?.let { state ->
            try { setOrbState(OrbState.valueOf(state)) } catch (_: Exception) {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        orbView?.stopAnimationLoop()
        unregisterStateReceiver()
        destroyOrb()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        orbView?.stopAnimationLoop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForeground() {
        val channelId = "cipher_orb_channel"
        val channel = NotificationChannel(
            channelId, "Vance Floating Orb",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Vance floating orb overlay" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Vance")
            .setContentText("Floating orb active")
            .setSmallIcon(R.drawable.cipher_orb)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1003, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1003, notification)
        }
    }

    fun setOrbState(state: OrbState) {
        Log.d(TAG, "Orb state: $state")
        orbView?.setState(state)
    }

    private fun registerStateReceiver() {
        stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getStringExtra(VoicePipeline.EXTRA_STATE) ?: return
                try { setOrbState(OrbState.valueOf(state)) } catch (_: Exception) {}
            }
        }
        val filter = IntentFilter(VoicePipeline.ACTION_STATE_CHANGE)
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterStateReceiver() {
        stateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            stateReceiver = null
        }
    }

    private fun createOrb() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — orb cannot show")
            return
        }
        val params = WindowManager.LayoutParams().apply {
            width = 160
            height = 160
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.END
            x = 24
            y = 120
        }

        orbView = OrbCanvasView(this).apply {
            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false
                private var tapCount = 0
                private var lastTapTime = 0L
                private var longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
                private var longPressTriggered = false
                private var longPressStartTime = 0L

                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    when (event?.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            longPressTriggered = false
                            longPressStartTime = System.currentTimeMillis()
                            // Schedule long-press detection at 600ms
                            longPressHandler.postDelayed({
                                if (!isDragging && !longPressTriggered) {
                                    longPressTriggered = true
                                    longPressHandler.removeCallbacksAndMessages(null)
                                    onOrbLongPress()
                                }
                            }, 600L)
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = abs(event.rawX - initialTouchX)
                            val dy = abs(event.rawY - initialTouchY)
                            if (dx > 10 || dy > 10) {
                                isDragging = true
                                longPressHandler.removeCallbacksAndMessages(null)
                            }
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(orbView, params)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            longPressHandler.removeCallbacksAndMessages(null)
                            if (longPressTriggered) {
                                // Already handled by long-press
                                return true
                            }
                            if (!isDragging) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 300) {
                                    tapCount++
                                } else {
                                    tapCount = 1
                                }
                                lastTapTime = now

                                when (tapCount) {
                                    1 -> {
                                        // Single tap → expand chat
                                        serviceScope.launch {
                                            delay(310)
                                            if (tapCount == 1) onOrbTap()
                                        }
                                    }
                                    2 -> {
                                        // Double tap → toggle mute
                                        tapCount = 0
                                        onOrbDoubleTap()
                                    }
                                }
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        try {
            windowManager?.addView(orbView, params)
            Log.d(TAG, "Floating orb created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create floating orb", e)
        }
    }

    private fun destroyOrb() {
        try {
            orbView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
    }

    private fun onOrbTap() {
        Log.d(TAG, "Orb tapped — expanding chat UI")
        val intent = Intent(this, VanceChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("show_chat", true)
        }
        startActivity(intent)
    }

    private fun onOrbDoubleTap() {
        Log.d(TAG, "Orb double-tapped — toggling mute")
        // Toggle mute via broadcast
        sendBroadcast(Intent("com.aetheria.vance.TOGGLE_MUTE").apply {
            setPackage(packageName)
        })
    }

    private fun onOrbLongPress() {
        Log.d(TAG, "Orb long-pressed — opening feedback UI")
        // Long press → open chat with feedback prompt
        val intent = Intent(this, VanceChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("show_chat", true)
            putExtra("feedback_mode", true)
        }
        startActivity(intent)
    }
}

// ── Custom Canvas View for Orb Animations ─────────────────────────

class OrbCanvasView(context: Context) : FrameLayout(context) {

    private var currentState = FloatingOrbService.OrbState.IDLE
    private var animationTime = 0f
    private var isMuted = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var isAnimationPaused = false
    private val idlePauseRunnable = Runnable {
        if (currentState == FloatingOrbService.OrbState.IDLE) {
            animator?.pause()
            isAnimationPaused = true
        }
    }

    init {
        setWillNotDraw(false)
        startAnimationLoop()
    }

    fun setState(state: FloatingOrbService.OrbState) {
        currentState = state
        when (state) {
            FloatingOrbService.OrbState.IDLE -> {
                postDelayed(idlePauseRunnable, 500L)
            }
            else -> {
                removeCallbacks(idlePauseRunnable)
                if (isAnimationPaused) {
                    animator?.resume()
                    isAnimationPaused = false
                }
            }
        }
        invalidate()
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        invalidate()
    }

    fun startAnimationLoop() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 33L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                animationTime = (animationTime + 0.033f) % 100f
                invalidate()
            }
            start()
        }
    }

    fun stopAnimationLoop() {
        removeCallbacks(null)
        animator?.cancel()
        animator = null
        isAnimationPaused = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = width / 3f

        when (currentState) {
            FloatingOrbService.OrbState.IDLE -> drawIdleOrb(canvas, cx, cy, baseRadius)
            FloatingOrbService.OrbState.LISTENING -> drawListeningOrb(canvas, cx, cy, baseRadius)
            FloatingOrbService.OrbState.THINKING -> drawThinkingOrb(canvas, cx, cy, baseRadius)
            FloatingOrbService.OrbState.SPEAKING -> drawSpeakingOrb(canvas, cx, cy, baseRadius)
            FloatingOrbService.OrbState.ERROR -> drawErrorOrb(canvas, cx, cy, baseRadius)
        }

        if (isMuted) {
            paint.color = Color.argb(128, 255, 0, 0)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawLine(cx - 15, cy - 15, cx + 15, cy + 15, paint)
        }
    }

    private fun drawIdleOrb(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        // Breathing pulse
        val pulse = sin(animationTime * 2f) * 0.1f + 1f
        val radius = baseRadius * pulse

        // Outer glow
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(40, 108, 99, 255)
        canvas.drawCircle(cx, cy, radius * 1.3f, paint)

        // Main orb
        paint.color = Color.argb(200, 108, 99, 255)
        canvas.drawCircle(cx, cy, radius, paint)

        // Inner highlight
        paint.color = Color.argb(100, 255, 255, 255)
        canvas.drawCircle(cx - radius * 0.2f, cy - radius * 0.2f, radius * 0.3f, paint)
    }

    private fun drawListeningOrb(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        // Expanding wave ripples
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f

        for (i in 0..2) {
            val phase = (animationTime * 3f + i * 0.33f) % 1f
            val radius = baseRadius * (0.8f + phase * 0.8f)
            val alpha = ((1f - phase) * 200).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, 0, 218, 198)
            canvas.drawCircle(cx, cy, radius, paint)
        }

        // Main orb
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 0, 218, 198)
        canvas.drawCircle(cx, cy, baseRadius * 0.8f, paint)
    }

    private fun drawThinkingOrb(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        // Spinning arc
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.argb(220, 255, 160, 0)

        val sweepAngle = (animationTime * 360f) % 360f
        val rect = RectF(cx - baseRadius, cy - baseRadius, cx + baseRadius, cy + baseRadius)
        canvas.drawArc(rect, sweepAngle, 120f, false, paint)

        // Inner orb
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(100, 255, 160, 0)
        canvas.drawCircle(cx, cy, baseRadius * 0.6f, paint)
    }

    private fun drawSpeakingOrb(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        // Audio waveform bars
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 76, 175, 80)

        val barCount = 5
        val barWidth = baseRadius * 0.15f
        val spacing = barWidth * 1.5f
        val startX = cx - (barCount * spacing) / 2f

        for (i in 0 until barCount) {
            val phase = sin(animationTime * 8f + i * 0.8f)
            val barHeight = baseRadius * 0.3f + phase * baseRadius * 0.4f
            val left = startX + i * spacing
            val top = cy - barHeight / 2f
            canvas.drawRoundRect(
                left, top, left + barWidth, top + barHeight,
                barWidth / 2, barWidth / 2, paint
            )
        }

        // Main orb
        paint.color = Color.argb(100, 76, 175, 80)
        canvas.drawCircle(cx, cy, baseRadius * 0.5f, paint)
    }

    private fun drawErrorOrb(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        // Red flash
        val flash = if (animationTime < 0.5f) 1f else 0.3f

        paint.style = Paint.Style.FILL
        paint.color = Color.argb((200 * flash).toInt(), 244, 67, 54)
        canvas.drawCircle(cx, cy, baseRadius, paint)

        // Reset to idle after brief flash
        if (animationTime > 1f) {
            currentState = FloatingOrbService.OrbState.IDLE
            animationTime = 0f
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
