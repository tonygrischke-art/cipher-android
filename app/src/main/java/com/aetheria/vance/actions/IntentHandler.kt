package com.aetheria.vance.actions

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import com.aetheria.vance.shizuku.ShizukuBridge

class IntentHandler(
    private val context: Context
) {

    companion object {
        private const val TAG = "IntentHandler"
        private const val PRINT_SETTINGS = "android.settings.PRINT_SETTINGS"
    }

    // ── Open App ────────────────────────────────────────────────────

    fun openApp(packageName: String): ActionExecutor.ActionResult {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ActionExecutor.ActionResult(true, "Opened $packageName", "Opening app")
            } catch (e: Exception) {
                ActionExecutor.ActionResult(false, e.message ?: "Launch failed", "I couldn't open that app")
            }
        } else {
            ActionExecutor.ActionResult(false, "No launch intent for $packageName", "I couldn't find that app")
        }
    }

    // ── Open App by Name (fuzzy match) ──────────────────────────────

    fun openAppByName(appName: String): ActionExecutor.ActionResult {
        val pm = context.packageManager
        val installedApps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        val query = appName.lowercase().trim()

        // Exact label match first
        val exactMatch = installedApps.firstOrNull { app ->
            pm.getApplicationLabel(app).toString().lowercase() == query
        }
        if (exactMatch != null) {
            return openApp(exactMatch.packageName)
        }

        // Prefix match
        val prefixMatch = installedApps.firstOrNull { app ->
            pm.getApplicationLabel(app).toString().lowercase().startsWith(query)
        }
        if (prefixMatch != null) {
            return openApp(prefixMatch.packageName)
        }

        // Contains match
        val containsMatch = installedApps.firstOrNull { app ->
            pm.getApplicationLabel(app).toString().lowercase().contains(query)
        }
        if (containsMatch != null) {
            return openApp(containsMatch.packageName)
        }

        // Known app name mappings
        val knownMappings = mapOf(
            "settings" to "com.android.settings",
            "camera" to "com.android.camera",
            "gallery" to "com.android.gallery",
            "photos" to "com.google.android.apps.photos",
            "maps" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.android.dialer",
            "dialer" to "com.android.dialer",
            "contacts" to "com.android.contacts",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator",
            "files" to "com.android.files",
            "file manager" to "com.android.files",
            "play store" to "com.android.vending",
            "store" to "com.android.vending",
            "gmail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "drive" to "com.google.android.apps.docs",
            "google" to "com.google.android.googlequicksearchbox",
            "browser" to "com.android.chrome",
            "netflix" to "com.netflix.mediaclient",
            "discord" to "com.discord",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "instagram" to "com.instagram.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "reddit" to "com.reddit.frontpage",
            "linkedin" to "com.linkedin.android",
            "uber" to "com.ubercab",
            "lyft" to "me.lyft.android",
            "terminal" to "com.termux",
            "termux" to "com.termux"
        )

        val mappedPackage = knownMappings[query]
        if (mappedPackage != null) {
            return openApp(mappedPackage)
        }

        return ActionExecutor.ActionResult(false, "App not found: $appName", "I couldn't find an app called $appName")
    }

    // ── Open URL ────────────────────────────────────────────────────

    fun openUrl(url: String): ActionExecutor.ActionResult {
        val normalizedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionExecutor.ActionResult(true, "Opened $normalizedUrl", "Opening link")
        } catch (e: Exception) {
            ActionExecutor.ActionResult(false, e.message ?: "URL open failed", "I couldn't open that link")
        }
    }

    // ── Share Text ──────────────────────────────────────────────────

    fun shareText(text: String): ActionExecutor.ActionResult {
        if (text.isBlank()) {
            return ActionExecutor.ActionResult(false, "Nothing to share", "What should I share?")
        }
        return try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Share via")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            ActionExecutor.ActionResult(true, "Sharing text", "Sharing that")
        } catch (e: Exception) {
            ActionExecutor.ActionResult(false, e.message ?: "Share failed", "I couldn't share that")
        }
    }

    // ── Open Settings ───────────────────────────────────────────────

    fun openSettings(settingsAction: String): ActionExecutor.ActionResult {
        val actionMapping = mapOf(
            "settings" to Settings.ACTION_SETTINGS,
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "security" to Settings.ACTION_SECURITY_SETTINGS,
            "privacy" to Settings.ACTION_PRIVACY_SETTINGS,
            "apps" to Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS,
            "notifications" to Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "keyboard" to Settings.ACTION_INPUT_METHOD_SETTINGS,
            "language" to Settings.ACTION_LOCALE_SETTINGS,
            "date" to Settings.ACTION_DATE_SETTINGS,
            "nfc" to Settings.ACTION_NFC_SETTINGS,
            "cast" to Settings.ACTION_CAST_SETTINGS,
            "print" to PRINT_SETTINGS,
            "about" to Settings.ACTION_DEVICE_INFO_SETTINGS,
            "home" to Settings.ACTION_HOME_SETTINGS
        )

        val normalizedKey = settingsAction.lowercase().trim()
        val action = actionMapping[normalizedKey]

        return if (action != null) {
            try {
                val intent = Intent(action)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionExecutor.ActionResult(true, "Opened $settingsAction settings", "Opening settings")
            } catch (e: Exception) {
                ActionExecutor.ActionResult(false, e.message ?: "Settings open failed", "I couldn't open those settings")
            }
        } else {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionExecutor.ActionResult(true, "Opened settings", "Opening settings")
            } catch (e: Exception) {
                ActionExecutor.ActionResult(false, e.message ?: "Settings open failed", "I couldn't open settings")
            }
        }
    }

    // ── Set Alarm ───────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    fun setAlarm(hour: Int, minute: Int, label: String = ""): ActionExecutor.ActionResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ActionExecutor.ActionResult(false, "Invalid time: $hour:$minute", "That's not a valid time")
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val displayTime = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
            ActionExecutor.ActionResult(true, "Alarm set for $displayTime", "Alarm set for $displayTime")
        } catch (e: Exception) {
            Log.w(TAG, "Intent-based alarm failed, trying Shizuku", e)
            try {
                // Use alarm via Shizuku if intent fails
                ActionExecutor.ActionResult(false, e.message ?: "Alarm failed", "I couldn't set that alarm. Try opening the Clock app.")
            } catch (e2: Exception) {
                ActionExecutor.ActionResult(false, "Alarm failed", "I couldn't set that alarm")
            }
        }
    }

    // ── Set Timer ───────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    fun setTimer(seconds: Int, label: String = ""): ActionExecutor.ActionResult {
        if (seconds <= 0) {
            return ActionExecutor.ActionResult(false, "Invalid duration: ${seconds}s", "Timer must be at least 1 second")
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val displayTime = if (seconds >= 60) {
                "${seconds / 60} minute${if (seconds / 60 != 1) "s" else ""}"
            } else {
                "$seconds seconds"
            }
            ActionExecutor.ActionResult(true, "Timer set for $displayTime", "Timer set for $displayTime")
        } catch (e: Exception) {
            Log.e(TAG, "setTimer failed", e)
            ActionExecutor.ActionResult(false, e.message ?: "Timer failed", "I couldn't set that timer")
        }
    }

    // ── Navigation ──────────────────────────────────────────────────

    fun navigateTo(destination: String): ActionExecutor.ActionResult {
        return try {
            val encodedDest = Uri.encode(destination)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encodedDest"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionExecutor.ActionResult(true, "Navigating to $destination", "Starting navigation to $destination")
        } catch (e: Exception) {
            Log.e(TAG, "navigateTo failed", e)
            // Fallback to Google Maps web
            try {
                val encodedDest = Uri.encode(destination)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/$encodedDest"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ActionExecutor.ActionResult(true, "Showing $destination on maps", "Showing $destination on maps")
            } catch (e2: Exception) {
                ActionExecutor.ActionResult(false, "Navigation failed", "I couldn't start navigation")
            }
        }
    }

    // ── Get Installed Apps (for fuzzy search) ───────────────────────

    fun searchInstalledApps(query: String): List<Pair<String, String>> {
        val pm = context.packageManager
        val apps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }
        val q = query.lowercase().trim()

        return apps
            .asSequence()
            .filter { app ->
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                !isSystem || pm.getApplicationLabel(app).toString().lowercase().contains(q)
            }
            .map { app ->
                pm.getApplicationLabel(app).toString() to app.packageName
            }
            .filter { (label, _) -> label.lowercase().contains(q) }
            .take(10)
            .toList()
    }
}
