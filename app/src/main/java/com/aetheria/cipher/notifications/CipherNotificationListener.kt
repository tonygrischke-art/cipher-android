package com.aetheria.cipher.notifications

import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class CipherNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private const val PREFS_NAME = "cipher_notification_prefs"
    }

    private lateinit var prefs: SharedPreferences
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

        // Filter noise
        if (shouldFilter(packageName, title, text)) {
            return
        }

        Log.d(TAG, "Notification from $packageName: $title - $text")

        lastNotificationKey = sbn.key
        lastNotificationText = "$title: $text"

        // Notify CipherCore
        notifyCipherCore(packageName, title, text)

        // Optional read-aloud
        if (shouldReadAloud(packageName)) {
            readAloud("$title. $text")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Log.d(TAG, "Notification removed from ${sbn?.packageName}")
    }

    private fun shouldFilter(packageName: String, title: String, text: String): Boolean {
        // Filter system/silent notifications
        val noisePatterns = listOf(
            "android.system", "low battery", "charging", "USB debugging",
            "Screen pinning", "VPN", "running in the background"
        )
        val fullText = "$title $text".lowercase()
        return noisePatterns.any { fullText.contains(it.lowercase()) }
    }

    private fun shouldReadAloud(packageName: String): Boolean {
        return prefs.getBoolean("read_aloud_$packageName", false)
    }

    private fun notifyCipherCore(packageName: String, title: String, text: String) {
        // TODO: Send notification event to CipherCoreService
        Log.d(TAG, "Would notify CipherCore: [$packageName] $title: $text")
    }

    private fun readAloud(text: String) {
        // TODO: Send TTS request to VoicePipeline via CipherCore
        Log.d(TAG, "Read aloud: $text")
    }

    fun getRecentNotification(): String? = lastNotificationText

    fun setReadAloudEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean("read_aloud_$packageName", enabled).apply()
    }
}
