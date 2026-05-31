package com.aetheria.cipher.notifications

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlin.math.min

class CipherNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private const val PREFS_NAME = "cipher_notification_prefs"
        const val ACTION_NOTIFICATION_RECEIVED = "com.aetheria.cipher.NOTIFICATION_RECEIVED"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TIMESTAMP = "timestamp"
        private const val MAX_BUFFER_SIZE = 10
    }

    data class NotificationSummary(
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val timestamp: Long
    )

    private lateinit var prefs: SharedPreferences
    private val notificationBuffer = ArrayDeque<NotificationSummary>(MAX_BUFFER_SIZE)
    private var lastNotificationKey: String? = null
    private var lastNotificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "CipherNotificationListener created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        if (shouldFilter(packageName, title, text)) return

        val appName = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) { packageName }

        val summary = NotificationSummary(
            packageName = packageName,
            appName = appName,
            title = title,
            text = text,
            timestamp = sbn.postTime
        )

        // Thread-safe buffer update
        synchronized(notificationBuffer) {
            if (notificationBuffer.size >= MAX_BUFFER_SIZE) {
                notificationBuffer.removeFirst()
            }
            notificationBuffer.addLast(summary)
        }

        lastNotificationKey = sbn.key
        lastNotificationText = "$title: $text"

        Log.d(TAG, "Notification from $appName ($packageName): $title — $text")

        // Broadcast for ContextEngine / RoutineEngine
        sendBroadcast(Intent(ACTION_NOTIFICATION_RECEIVED).apply {
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_TIMESTAMP, sbn.postTime)
            setPackage(packageName)
        })
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Log.d(TAG, "Notification removed from ${sbn?.packageName}")
    }

    // ── Public API ──────────────────────────────────────────────────

    fun getLastNotification(): NotificationSummary? {
        return synchronized(notificationBuffer) {
            notificationBuffer.lastOrNull()
        }
    }

    fun getLastNotificationText(): String? = lastNotificationText

    fun getRecentNotifications(count: Int = 5): List<NotificationSummary> {
        return synchronized(notificationBuffer) {
            notificationBuffer.takeLast(min(count, notificationBuffer.size))
        }
    }

    fun getAllNotifications(): List<NotificationSummary> {
        return synchronized(notificationBuffer) {
            notificationBuffer.toList()
        }
    }

    fun getNotificationCount(): Int {
        return synchronized(notificationBuffer) { notificationBuffer.size }
    }

    fun setReadAloudEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean("read_aloud_$packageName", enabled).apply()
    }

    fun isReadAloudEnabled(packageName: String): Boolean {
        return prefs.getBoolean("read_aloud_$packageName", false)
    }

    fun getRecentNotification(): String? = lastNotificationText

    private fun shouldFilter(packageName: String, title: String, text: String): Boolean {
        val noisePatterns = listOf(
            "android.system", "low battery", "charging", "USB debugging",
            "Screen pinning", "VPN", "running in the background",
            "media playback", "tap to manage", "notification"
        )
        val fullText = "$title $text".lowercase()
        return noisePatterns.any { fullText.contains(it.lowercase()) }
    }
}
