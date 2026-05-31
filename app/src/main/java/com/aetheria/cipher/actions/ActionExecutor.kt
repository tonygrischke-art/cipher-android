package com.aetheria.cipher.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aetheria.cipher.shizuku.ShizukuBridge
import com.aetheria.cipher.shizuku.ShizukuNotAvailableException
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActionExecutor(
    private val context: Context,
    private val shizukuBridge: ShizukuBridge
) {

    companion object {
        private const val TAG = "ActionExecutor"
        private const val SMS_MAX_LENGTH = 160
    }

    data class ActionResult(
        val success: Boolean,
        val output: String,
        val spokenResponse: String
    )

    data class ActionLogEntry(
        val timestamp: String,
        val actionType: String,
        val command: String,
        val result: String
    )

    private val actionLog = mutableListOf<ActionLogEntry>()

    fun getActionLog(): List<ActionLogEntry> = actionLog.toList()

    suspend fun execute(actionJson: String): ActionResult {
        Log.d(TAG, "Executing action: $actionJson")

        return try {
            val json = JSONObject(actionJson)
            val actionType = json.optString("action_type", "UNKNOWN")
            val params = json.optJSONObject("parameters") ?: json.optJSONObject("params") ?: JSONObject()
            val spokenOverride = json.optString("spoken_response", "")

            val result = when (actionType) {
                "SHELL_COMMAND" -> handleShellCommand(params)
                "OPEN_APP" -> handleOpenApp(params)
                "SEND_SMS" -> handleSendSms(params)
                "MAKE_CALL" -> handleMakeCall(params)
                "WEB_SEARCH" -> handleWebSearch(params)
                "SYSTEM_SETTING" -> handleSystemSetting(params)
                "TAKE_SCREENSHOT" -> handleTakeScreenshot(params)
                "NOTIFICATION_ACTION" -> handleNotificationAction(params)
                "ACCESSIBILITY_ACTION" -> handleAccessibilityAction(params)
                "UNKNOWN" -> ActionResult(true, "No action specified", "I don't know how to do that yet")
                else -> ActionResult(true, "Unknown action: $actionType", "I don't know how to do that yet ($actionType)")
            }

            val spoken = spokenOverride.ifBlank { result.spokenResponse }
            logAction(actionType, params.toString(), if (result.success) "success" else result.output)
            result.copy(spokenResponse = spoken)
        } catch (e: Exception) {
            Log.e(TAG, "Action execution failed", e)
            logAction("ERROR", actionJson, e.message ?: "unknown error")
            ActionResult(
                success = false,
                output = e.message ?: "Unknown error",
                spokenResponse = "I ran into a problem: ${e.message ?: "unknown error"}"
            )
        }
    }

    fun executeStreaming(actionJson: String): Flow<String> {
        Log.d(TAG, "Executing streaming action: $actionJson")

        return try {
            val json = JSONObject(actionJson)
            val actionType = json.optString("action_type", "UNKNOWN")
            val params = json.optJSONObject("parameters") ?: json.optJSONObject("params") ?: JSONObject()

            when (actionType) {
                "SHELL_COMMAND" -> {
                    val cmd = params.optString("command", "")
                    if (cmd.isBlank()) return kotlinx.coroutines.flow.flowOf("No command specified")
                    shizukuBridge.executeCommand(cmd)
                }
                else -> kotlinx.coroutines.flow.flow {
                    val result = execute(actionJson)
                    emit(result.output)
                }
            }
        } catch (e: Exception) {
            kotlinx.coroutines.flow.flowOf("Error: ${e.message}")
        }
    }

    // ── SHELL_COMMAND ───────────────────────────────────────────────

    private suspend fun handleShellCommand(params: JSONObject): ActionResult {
        val command = params.optString("command", "")
        if (command.isBlank()) {
            return ActionResult(false, "No command specified", "No command was specified")
        }

        return try {
            val output = shizukuBridge.executeBlocking(command)
            ActionResult(true, output, "Command executed successfully")
        } catch (e: ShizukuNotAvailableException) {
            ActionResult(false, e.message ?: "Shizuku not available", "Shizuku is not available. Open Shizuku and start the service.")
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Command failed", "Failed to run that command")
        }
    }

    // ── OPEN_APP ────────────────────────────────────────────────────

    private fun handleOpenApp(params: JSONObject): ActionResult {
        val packageName = params.optString("package_name", params.optString("package", ""))
        if (packageName.isBlank()) {
            return ActionResult(false, "No package name specified", "I don't know which app to open")
        }

        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ActionResult(true, "Launched $packageName", "Opening app")
            } else {
                try {
                    val output = kotlinx.coroutines.runBlocking {
                        shizukuBridge.executeBlocking("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                    }
                    ActionResult(true, output, "Opening app via monkey")
                } catch (e: Exception) {
                    ActionResult(false, "App not found: $packageName", "I couldn't find that app")
                }
            }
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Failed to launch app", "I ran into a problem opening that app")
        }
    }

    // ── SEND_SMS ────────────────────────────────────────────────────

    private fun handleSendSms(params: JSONObject): ActionResult {
        val phoneNumber = params.optString("phone_number", params.optString("to", ""))
        val message = params.optString("message", "")
        val contactName = params.optString("contact", "")

        if (message.isBlank()) {
            return ActionResult(false, "No message specified", "What message should I send?")
        }

        val resolvedNumber = if (phoneNumber.isBlank() && contactName.isNotBlank()) {
            resolveContact(contactName)
        } else {
            phoneNumber
        }

        if (resolvedNumber.isBlank()) {
            return ActionResult(false, "No valid phone number", "I couldn't find a number for that contact")
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult(false, "SEND_SMS permission not granted", "I don't have permission to send texts")
        }

        return try {
            val smsManager = SmsManager.getDefault()
            if (message.length > SMS_MAX_LENGTH) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(resolvedNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(resolvedNumber, null, message, null, null)
            }

            val contactDisplay = if (contactName.isNotBlank()) contactName else resolvedNumber
            ActionResult(true, "SMS sent to $contactDisplay", "Message sent to $contactDisplay")
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "SMS send failed", "I couldn't send that message")
        }
    }

    private fun resolveContact(name: String): String {
        try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(0) ?: ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact resolution failed", e)
        }
        return ""
    }

    // ── MAKE_CALL ───────────────────────────────────────────────────

    private fun handleMakeCall(params: JSONObject): ActionResult {
        val phoneNumber = params.optString("phone_number", params.optString("number", ""))
        if (phoneNumber.isBlank()) {
            return ActionResult(false, "No phone number specified", "What number should I call?")
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult(false, "CALL_PHONE permission not granted", "I don't have permission to make calls")
        }

        return try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(callIntent)
            ActionResult(true, "Calling $phoneNumber", "Calling $phoneNumber")
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Call failed", "I couldn't make that call")
        }
    }

    // ── WEB_SEARCH ──────────────────────────────────────────────────

    private fun handleWebSearch(params: JSONObject): ActionResult {
        val query = params.optString("query", "")
        if (query.isBlank()) {
            return ActionResult(false, "No search query", "What should I search for?")
        }

        return try {
            val encodedQuery = Uri.encode(query)
            val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encodedQuery"))
            searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(searchIntent)
            ActionResult(true, "Searching for: $query", "Searching for $query")
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Search failed", "I couldn't open the search")
        }
    }

    // ── SYSTEM_SETTING ──────────────────────────────────────────────

    private suspend fun handleSystemSetting(params: JSONObject): ActionResult {
        val setting = params.optString("setting", "")
        val value = params.optString("value", "")

        if (setting.isBlank()) {
            return ActionResult(false, "No setting specified", "What setting should I change?")
        }

        return when (setting) {
            "wifi_on" -> runShizukuSetting("svc wifi enable", "Turning on Wi-Fi")
            "wifi_off" -> runShizukuSetting("svc wifi disable", "Turning off Wi-Fi")
            "bluetooth_on" -> runShizukuSetting("cmd bluetooth_manager enable", "Turning on Bluetooth")
            "bluetooth_off" -> runShizukuSetting("cmd bluetooth_manager disable", "Turning off Bluetooth")
            "flashlight_on" -> handleFlashlight(true)
            "flashlight_off" -> handleFlashlight(false)
            "airplane_on" -> runShizukuSetting(
                "settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
                "Turning on airplane mode"
            )
            "airplane_off" -> runShizukuSetting(
                "settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false",
                "Turning off airplane mode"
            )
            "volume_up" -> handleVolumeUp()
            "volume_down" -> handleVolumeDown()
            "brightness" -> handleBrightness(value)
            "dnd_on" -> runShizukuSetting("cmd notification set_dnd priority", "Turning on Do Not Disturb")
            "dnd_off" -> runShizukuSetting("cmd notification set_dnd off", "Turning off Do Not Disturb")
            else -> ActionResult(false, "Unknown setting: $setting", "I don't know how to change that setting")
        }
    }

    private suspend fun runShizukuSetting(command: String, spoken: String): ActionResult {
        return try {
            val output = shizukuBridge.executeBlocking(command)
            ActionResult(true, output, spoken)
        } catch (e: ShizukuNotAvailableException) {
            ActionResult(false, e.message ?: "Shizuku not available", "Shizuku is not available")
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Setting failed", "I couldn't change that setting")
        }
    }

    private fun handleFlashlight(on: Boolean): ActionResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, on)
                val spoken = if (on) "Flashlight on" else "Flashlight off"
                ActionResult(true, spoken, spoken)
            } else {
                ActionResult(false, "No flashlight available", "This device doesn't have a flashlight")
            }
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Flashlight failed", "I couldn't toggle the flashlight")
        }
    }

    private fun handleVolumeUp(): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            ActionResult(true, "Volume raised", "Volume up")
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Volume change failed", "I couldn't change the volume")
        }
    }

    private fun handleVolumeDown(): ActionResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            ActionResult(true, "Volume lowered", "Volume down")
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Volume change failed", "I couldn't change the volume")
        }
    }

    private fun handleBrightness(value: String): ActionResult {
        val brightness = value.toIntOrNull()
        if (brightness == null || brightness < 0 || brightness > 255) {
            return ActionResult(false, "Invalid brightness value: $value", "Brightness must be between 0 and 255")
        }

        return try {
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness
                )
                ActionResult(true, "Brightness set to $brightness", "Brightness set")
            } else {
                try {
                    val output = kotlinx.coroutines.runBlocking {
                        shizukuBridge.executeBlocking("settings put system screen_brightness $brightness")
                    }
                    ActionResult(true, output, "Brightness set via Shizuku")
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(false, "WRITE_SETTINGS permission needed", "I need permission to change brightness. Please grant it in settings.")
                }
            }
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Brightness change failed", "I couldn't change the brightness")
        }
    }

    // ── TAKE_SCREENSHOT ─────────────────────────────────────────────

    private suspend fun handleTakeScreenshot(params: JSONObject): ActionResult {
        val outputPath = params.optString("output_path", "/sdcard/cipher_screenshot.png")
        return try {
            val output = shizukuBridge.executeBlocking("screencap -p $outputPath")
            ActionResult(true, output, "Screenshot saved to $outputPath")
        } catch (e: ShizukuNotAvailableException) {
            ActionResult(false, e.message ?: "Shizuku not available", "Shizuku is not available for screenshots")
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Screenshot failed", "I couldn't take a screenshot")
        }
    }

    // ── NOTIFICATION_ACTION ─────────────────────────────────────────

    private fun handleNotificationAction(params: JSONObject): ActionResult {
        val action = params.optString("action", "")
        val key = params.optString("key", "")

        return when (action) {
            "dismiss" -> {
                try {
                    val intent = Intent("com.aetheria.cipher.DISMISS_NOTIFICATION").apply {
                        putExtra("notification_key", key)
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(intent)
                    ActionResult(true, "Notification dismissed", "Notification dismissed")
                } catch (e: Exception) {
                    ActionResult(false, e.message ?: "Dismiss failed", "I couldn't dismiss that notification")
                }
            }
            "read_last" -> {
                val intent = Intent("com.aetheria.cipher.READ_NOTIFICATION").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
                ActionResult(true, "Reading last notification", "Reading your last notification")
            }
            else -> ActionResult(false, "Unknown notification action: $action", "I don't know how to do that with notifications")
        }
    }

    // ── ACCESSIBILITY_ACTION ────────────────────────────────────────

    private fun handleAccessibilityAction(params: JSONObject): ActionResult {
        val action = params.optString("action", "")
        val target = params.optString("target", "")

        if (action.isBlank()) {
            return ActionResult(false, "No accessibility action specified", "What should I do?")
        }

        return try {
            val intent = Intent("com.aetheria.cipher.ACCESSIBILITY_ACTION").apply {
                putExtra("action", action)
                putExtra("target", target)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            ActionResult(true, "$action on '$target'", "Done: $action on $target")
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Accessibility action failed", "I couldn't perform that action")
        }
    }

    // ── Logging ─────────────────────────────────────────────────────

    private fun logAction(actionType: String, command: String, result: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = ActionLogEntry(timestamp, actionType, command, result)
        actionLog.add(entry)
        Log.d(TAG, "[$timestamp] $actionType | $command | $result")

        // Keep log bounded
        if (actionLog.size > 500) {
            actionLog.removeAt(0)
        }
    }
}
