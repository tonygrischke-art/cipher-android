package com.aetheria.cipher.context

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContextEngine(private val context: Context) {

    companion object {
        private const val TAG = "ContextEngine"
    }

    data class ContextSnapshot(
        val timestamp: String,
        val dayOfWeek: String,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val foregroundApp: String,
        val location: String,
        val nextCalendarEvent: String,
        val lastNotification: String,
        val networkType: String,
        val screenBrightness: Int,
        val volumeLevel: Int
    )

    fun getCurrentContext(): String {
        val snapshot = buildContextSnapshot()
        return formatContextString(snapshot)
    }

    private fun buildContextSnapshot(): ContextSnapshot {
        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val dayFormat = SimpleDateFormat("EEEE", Locale.US)

        return ContextSnapshot(
            timestamp = dateFormat.format(now),
            dayOfWeek = dayFormat.format(now),
            batteryLevel = getBatteryLevel(),
            isCharging = isCharging(),
            foregroundApp = getForegroundApp(),
            location = getLocation(),
            nextCalendarEvent = getNextCalendarEvent(),
            lastNotification = getLastNotification(),
            networkType = getNetworkType(),
            screenBrightness = getScreenBrightness(),
            volumeLevel = getMediaVolume()
        )
    }

    private fun formatContextString(snapshot: ContextSnapshot): String {
        return buildString {
            appendLine("[Time] ${snapshot.timestamp} (${snapshot.dayOfWeek})")
            appendLine("[Battery] ${snapshot.batteryLevel}%${if (snapshot.isCharging) " ⚡charging" else ""}")
            appendLine("[App] ${snapshot.foregroundApp}")
            appendLine("[Location] ${snapshot.location}")
            if (snapshot.nextCalendarEvent.isNotBlank()) {
                appendLine("[Next Event] ${snapshot.nextCalendarEvent}")
            }
            if (snapshot.lastNotification.isNotBlank()) {
                appendLine("[Last Notification] ${snapshot.lastNotification}")
            }
            appendLine("[Network] ${snapshot.networkType}")
            appendLine("[Brightness] ${snapshot.screenBrightness}")
            appendLine("[Volume] ${snapshot.volumeLevel}")
        }.trim()
    }

    private fun getBatteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getForegroundApp(): String {
        // TODO: Query UsageStatsManager for current foreground app
        return "unknown"
    }

    private fun getLocation(): String {
        return "city-level (disabled in stub)"
    }

    private fun getNextCalendarEvent(): String {
        // TODO: Query CalendarContract for upcoming event
        return ""
    }

    private fun getLastNotification(): String {
        return ""
    }

    private fun getNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }

    private fun getScreenBrightness(): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { -1 }
    }

    private fun getMediaVolume(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        return am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
    }
}
