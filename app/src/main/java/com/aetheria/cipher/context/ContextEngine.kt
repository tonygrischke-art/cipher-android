package com.aetheria.cipher.context

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class ContextEngine(private val context: Context) {

    companion object {
        private const val TAG = "ContextEngine"
        private const val TIMEOUT_PER_FIELD_MS = 2_000L
        private const val CACHE_TTL_MS = 10_000L
    }

    data class ContextSnapshot(
        val timestamp: Long = System.currentTimeMillis(),
        val timeFormatted: String = "",
        val batteryLevel: Int = -1,
        val isCharging: Boolean = false,
        val networkType: String = "unknown",
        val foregroundApp: String = "unknown",
        val screenBrightness: Int = -1,
        val volume: Int = -1,
        val nextCalendarEvent: String? = null,
        val lastNotification: String? = null,
        val locationCity: String? = null,
        val isDrivingMode: Boolean = false,
        val thermalState: String = "Unknown"
    )

    private var cachedSnapshot: ContextSnapshot? = null
    private var lastSnapshotTime: Long = 0

    // Reference to last notification from CipherNotificationListener
    var lastNotification: String? = null
    var foregroundAppPackage: String = "unknown"
        set(value) { field = value }

    suspend fun getSnapshot(): ContextSnapshot {
        val now = System.currentTimeMillis()
        if (cachedSnapshot != null && (now - lastSnapshotTime) < CACHE_TTL_MS) {
            return cachedSnapshot!!
        }

        return withContext(Dispatchers.IO) {
            coroutineScope {
                val timeFormatted = withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) {
                    val sdf = SimpleDateFormat("EEEE h:mm a", Locale.US)
                    sdf.format(Date())
                } ?: ""

                val batteryDeferred = async { withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) { getBatteryLevel() } ?: -1 }
                val chargingDeferred = async { withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) { isCharging() } ?: false }
                val networkDeferred = async { withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) { getNetworkType() } ?: "unknown" }
                val brightnessDeferred = async { withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) { getScreenBrightness() } ?: -1 }
                val volumeDeferred = async { withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) { getMediaVolume() } ?: -1 }
                val calendarDeferred = async { withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) { getNextCalendarEvent() } }
                val locationDeferred = async { withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) { getLocationCity() } }
                val thermalDeferred = async { withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) { getThermalState() } }

                val foregroundApp = try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(foregroundAppPackage, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) { foregroundAppPackage }

                val snapshot = ContextSnapshot(
                    timestamp = now,
                    timeFormatted = timeFormatted,
                    batteryLevel = batteryDeferred.await(),
                    isCharging = chargingDeferred.await(),
                    networkType = networkDeferred.await(),
                    foregroundApp = foregroundApp,
                    screenBrightness = brightnessDeferred.await(),
                    volume = volumeDeferred.await(),
                    nextCalendarEvent = calendarDeferred.await(),
                    lastNotification = lastNotification,
                    locationCity = locationDeferred.await(),
                    isDrivingMode = false,
                    thermalState = thermalDeferred.await() ?: "Unknown"
                )

                cachedSnapshot = snapshot
                lastSnapshotTime = now
                snapshot
            }
        }
    }

    fun formatForPrompt(snapshot: ContextSnapshot): String {
        return buildString {
            append("[${snapshot.timeFormatted}")
            if (snapshot.batteryLevel >= 0) {
                append(" | Battery ${snapshot.batteryLevel}%")
                if (snapshot.isCharging) append(" charging")
            }
            if (snapshot.networkType != "unknown") {
                append(" | ${snapshot.networkType}")
            }
            if (snapshot.foregroundApp != "unknown") {
                append(" | Foreground: ${snapshot.foregroundApp}")
            }
            if (snapshot.nextCalendarEvent != null) {
                append(" | Next: ${snapshot.nextCalendarEvent}")
            }
            if (snapshot.locationCity != null) {
                append(" | ${snapshot.locationCity}")
            }
            if (snapshot.lastNotification != null) {
                append(" | Notif: ${snapshot.lastNotification}")
            }
            if (snapshot.isDrivingMode) {
                append(" | Driving")
            }
            if (snapshot.thermalState != "Unknown") {
                append(" | ${snapshot.thermalState}")
            }
            append("]")
        }
    }

    fun getCurrentContext(): String {
        return runCatching {
            kotlinx.coroutines.runBlocking { formatForPrompt(getSnapshot()) }
        }.getOrDefault("[context unavailable]")
    }

    // ── Battery ─────────────────────────────────────────────────────

    private fun getBatteryLevel(): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (e: Exception) { -1 }
    }

    private fun isCharging(): Boolean {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) { false }
    }

    // ── Network ─────────────────────────────────────────────────────

    private fun getNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
            val network = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
                            as? android.net.wifi.WifiManager
                    val wifiInfo = wifiManager?.connectionInfo
                    val ssid = wifiInfo?.ssid?.removeSurrounding("\"") ?: "WiFi"
                    "WiFi: $ssid"
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
                            as? android.telephony.TelephonyManager
                    val networkType = when (tm?.dataNetworkType) {
                        android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                        android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                        android.telephony.TelephonyManager.NETWORK_TYPE_UMTS,
                        android.telephony.TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                        else -> "Mobile"
                    }
                    "Mobile $networkType"
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Offline"
            }
        } catch (e: Exception) { "unknown" }
    }

    // ── Screen Brightness ───────────────────────────────────────────

    private fun getScreenBrightness(): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) { -1 }
    }

    // ── Volume ──────────────────────────────────────────────────────

    private fun getMediaVolume(): Int {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        } catch (e: Exception) { -1 }
    }

    // ── Calendar ────────────────────────────────────────────────────

    private fun getNextCalendarEvent(): String? {
        return try {
            val now = System.currentTimeMillis()
            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.EVENT_LOCATION
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ?"
            val selectionArgs = arrayOf(now.toString())
            val sortOrder = "${CalendarContract.Events.DTSTART} ASC LIMIT 1"

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection, selection, selectionArgs, sortOrder
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val title = it.getString(0) ?: "Event"
                    val startTime = it.getLong(1)
                    val location = it.getString(2)
                    val minutesUntil = ((startTime - now) / 60000).toInt()
                    val locationStr = if (!location.isNullOrBlank()) " at $location" else ""
                    if (minutesUntil > 0) {
                        "in ${minutesUntil}m: $title$locationStr"
                    } else {
                        "Now: $title$locationStr"
                    }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Calendar query failed", e)
            null
        }
    }

    // ── Location ────────────────────────────────────────────────────

    private suspend fun getLocationCity(): String? {
        return withTimeoutOrNull(TIMEOUT_PER_FIELD_MS) {
            try {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                val location = suspendCancellableCoroutine<Location?> { cont ->
                    fusedClient.lastLocation
                        .addOnSuccessListener { loc -> cont.resume(loc) }
                        .addOnFailureListener { cont.resume(null) }
                    cont.invokeOnCancellation { /* no-op */ }
                }
                if (location != null) {
                    val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    addresses?.firstOrNull()?.let { addr ->
                        val city = addr.locality ?: addr.subAdminArea
                        val state = addr.adminArea
                        if (city != null && state != null) "$city, $state" else city ?: state
                    }
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Location query failed", e)
                null
            }
        }
    }

    // ── Thermal ─────────────────────────────────────────────────────

    private fun getThermalState(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                when (pm?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE,
                    PowerManager.THERMAL_STATUS_LIGHT -> "Normal"
                    PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
                    PowerManager.THERMAL_STATUS_SEVERE,
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "Hot"
                    else -> "Unknown"
                }
            } catch (e: Exception) { null }
        } else null
    }
}
