package com.aetheria.cipher.ui

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import com.aetheria.cipher.voice.VoicePipeline
import com.aetheria.cipher.MainActivity
import kotlin.math.abs

class FloatingOrbService : Service() {

    companion object {
        private const val TAG = "FloatingOrbService"
    }

    private var windowManager: WindowManager? = null
    private var orbView: FrameLayout? = null
    private var stateReceiver: BroadcastReceiver? = null

    enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOrb()
        registerStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("state")?.let { state ->
            try { setOrbState(OrbState.valueOf(state)) } catch (_: Exception) {}
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterStateReceiver()
        destroyOrb()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun setOrbState(state: OrbState) {
        Log.d(TAG, "Orb state: $state")
        // TODO: Update orb animation based on state
        // IDLE -> pulse animation
        // LISTENING -> wave animation
        // THINKING -> spinner
        // SPEAKING -> glow
    }

    private fun registerStateReceiver() {
        stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getStringExtra(VoicePipeline.EXTRA_STATE) ?: return
                try { setOrbState(OrbState.valueOf(state)) } catch (_: Exception) {}
            }
        }
        val filter = IntentFilter(VoicePipeline.ACTION_STATE_CHANGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UNSPECIFIED_REGISTER_RECEIVER_FLAG")
            registerReceiver(stateReceiver, filter)
        }
        Log.d(TAG, "State receiver registered")
    }

    private fun unregisterStateReceiver() {
        stateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            stateReceiver = null
        }
    }

    private fun createOrb() {
        val params = WindowManager.LayoutParams().apply {
            width = 160
            height = 160
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        orbView = FrameLayout(this).apply {
            // TODO: Set orb drawable

            setOnTouchListener(object : android.view.View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(v: android.view.View?, event: MotionEvent?): Boolean {
                    when (event?.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = abs(event.rawX - initialTouchX)
                            val dy = abs(event.rawY - initialTouchY)
                            if (dx > 10 || dy > 10) isDragging = true
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(orbView, params)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) onOrbTap()
                            return true
                        }
                    }
                    return false
                }
            })

            setOnClickListener { onOrbTap() }
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
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("show_chat", true)
        }
        startActivity(intent)
    }
}
