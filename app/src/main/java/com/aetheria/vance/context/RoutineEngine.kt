package com.aetheria.vance.context

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aetheria.vance.actions.ActionExecutor
import com.aetheria.vance.shizuku.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class RoutineEngine(
    private val context: Context,
    private val memoryStore: MemoryStore,
    private val actionExecutor: ActionExecutor,
    private val shizukuBridge: ShizukuBridge
) {
    companion object {
        private const val TAG = "RoutineEngine"
        const val ACTION_ROUTINE_FIRED = "com.aetheria.vance.ROUTINE_FIRED"
        const val ACTION_CHECK_TIME_ROUTINES = "com.aetheria.vance.CHECK_TIME_ROUTINES"
        const val EXTRA_ROUTINE_ID = "routine_id"
        const val EXTRA_ACTION_JSON = "action_json"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isInitialized = false

    init {
        registerReceivers()
        initializeDefaultRoutines()
        isInitialized = true
        Log.d(TAG, "RoutineEngine initialized")
    }

    // ── Broadcast Receivers ─────────────────────────────────────────

    private fun registerReceivers() {
        // Battery level receiver
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        // Time check receiver (triggered by AlarmManager every minute)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                timeCheckReceiver,
                IntentFilter(ACTION_CHECK_TIME_ROUTINES),
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UNSPECIFIED_REGISTER_RECEIVER_FLAG")
            context.registerReceiver(
                timeCheckReceiver,
                IntentFilter(ACTION_CHECK_TIME_ROUTINES)
            )
        }

        // App foreground change receiver
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                appChangeReceiver,
                IntentFilter(com.aetheria.vance.accessibility.VanceAccessibilityService.ACTION_FOREGROUND_APP_CHANGED),
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UNSPECIFIED_REGISTER_RECEIVER_FLAG")
            context.registerReceiver(
                appChangeReceiver,
                IntentFilter(com.aetheria.vance.accessibility.VanceAccessibilityService.ACTION_FOREGROUND_APP_CHANGED)
            )
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale

            // Check battery-level routines
            val batteryRoutines = memoryStore.routines.getByTriggerType("battery")
            for (routine in batteryRoutines) {
                val threshold = routine.triggerValue.toIntOrNull() ?: continue
                if (pct <= threshold) {
                    Log.d(TAG, "Battery routine triggered: ${routine.label} (level=$pct, threshold=$threshold)")
                    executeRoutine(routine)
                }
            }
        }
    }

    private val timeCheckReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            checkTimeRoutines()
        }
    }

    private val appChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val packageName = intent?.getStringExtra(
                com.aetheria.vance.accessibility.VanceAccessibilityService.EXTRA_PACKAGE_NAME
            ) ?: return

            val appRoutines = memoryStore.routines.getByTrigger("app", packageName)
            for (routine in appRoutines) {
                Log.d(TAG, "App routine triggered: ${routine.label} (app=$packageName)")
                executeRoutine(routine)
            }
        }
    }

    // ── Core Execution ─────────────────────────────────────────────

    private fun executeRoutine(routine: RoutineEntity) {
        scope.launch {
            try {
                val actionJson = routine.actionJson
                Log.d(TAG, "Executing routine '${routine.label}': $actionJson")

                if (actionJson.startsWith("{")) {
                    actionExecutor.execute(actionJson)
                } else {
                    // Plain text command via Shizuku
                    shizukuBridge.executeBlocking(actionJson)
                }

                // Update last run time
                memoryStore.routines.updateLastRun(routine.id, System.currentTimeMillis())
                Log.d(TAG, "Routine '${routine.label}' completed")

            } catch (e: Exception) {
                Log.e(TAG, "Routine '${routine.label}' failed: ${e.message}", e)
            }
        }
    }

    private fun checkTimeRoutines() {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = now.get(java.util.Calendar.MINUTE)
        val dayOfWeek = now.get(java.util.Calendar.DAY_OF_WEEK)
        val timeStr = String.format("%02d:%02d", hour, minute)

        val timeRoutines = memoryStore.routines.getByTriggerType("time")
        for (routine in timeRoutines) {
            // triggerValue format: "HH:MM" or "HH:MM:DAY" where day=1-7 (Sun=1)
            val parts = routine.triggerValue.split(":")
            if (parts.size >= 2) {
                val routineHour = parts[0].trim().toIntOrNull() ?: continue
                val routineMin = parts[1].trim().toIntOrNull() ?: continue
                if (routineHour == hour && routineMin == minute) {
                    // Check day if specified
                    if (parts.size >= 3) {
                        val routineDay = parts[2].trim().toIntOrNull() ?: continue
                        // Convert Calendar day (1=Sun) to check
                        if (routineDay != dayOfWeek) continue
                    }
                    executeRoutine(routine)
                }
            }
        }
    }

    // ── Default Routines ───────────────────────────────────────────

    private fun initializeDefaultRoutines() {
        if (memoryStore.routines.getEnabled().isNotEmpty()) return
        Log.d(TAG, "Creating default routines")

        // Morning briefing at 7:00 AM
        memoryStore.addRoutine(
            triggerType = "time",
            triggerValue = "07:00",
            actionJson = JSONObject().apply {
                put("action_type", "REASONING")
                put("parameters", JSONObject().apply {
                    put("query", "Give me a morning briefing: weather, calendar, notifications, and any important items.")
                })
                put("spoken_response", "Here's your morning briefing.")
            }.toString(),
            label = "Morning Briefing"
        )

        // Battery below 20% → enable power saving
        memoryStore.addRoutine(
            triggerType = "battery",
            triggerValue = "20",
            actionJson = JSONObject().apply {
                put("action_type", "SYSTEM_SETTING")
                put("parameters", JSONObject().apply {
                    put("setting", "battery_saver")
                    put("value", "on")
                })
                put("spoken_response", "Battery is low. I've enabled power saving mode.")
            }.toString(),
            label = "Low Battery Power Save"
        )

        // WiFi disconnect notification
        memoryStore.addRoutine(
            triggerType = "event",
            triggerValue = "wifi_disconnected",
            actionJson = JSONObject().apply {
                put("action_type", "NOTIFICATION_ACTION")
                put("parameters", JSONObject().apply {
                    put("action", "notify")
                    put("message", "WiFi disconnected")
                })
                put("spoken_response", "WiFi has disconnected.")
            }.toString(),
            label = "WiFi Disconnect Alert"
        )

        // Schedule the time-based alarm
        scheduleTimeCheckAlarm()
    }

    private fun scheduleTimeCheckAlarm() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ACTION_CHECK_TIME_ROUTINES).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intervalMs = 60_000L // every minute
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + intervalMs,
            intervalMs,
            pendingIntent
        )
        Log.d(TAG, "Time check alarm scheduled (every 60s)")
    }

    // ── Public API ─────────────────────────────────────────────────

    fun addCustomRoutine(
        triggerType: String,
        triggerValue: String,
        actionType: String,
        actionParams: Map<String, String>,
        label: String
    ): Long {
        val actionJson = JSONObject().apply {
            put("action_type", actionType)
            put("parameters", JSONObject().apply {
                actionParams.forEach { (k, v) -> put(k, v) }
            })
            put("spoken_response", "Routine '$label' executed.")
        }.toString()
        return memoryStore.addRoutine(triggerType, triggerValue, actionJson, label)
    }

    fun getActiveRoutines() = memoryStore.routines.getEnabled()

    fun disableRoutine(routineId: Long) {
        val routine = memoryStore.routines.getEnabled().find { it.id == routineId }
        if (routine != null) {
            memoryStore.routines.update(routine.copy(enabled = false))
        }
    }

    fun destroy() {
        try {
            context.unregisterReceiver(batteryReceiver)
            context.unregisterReceiver(timeCheckReceiver)
            context.unregisterReceiver(appChangeReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receivers", e)
        }
    }
}
