package com.aetheria.vance.actions

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import com.aetheria.vance.shizuku.ShizukuBridge

class SystemSettingHandler(
    private val context: Context,
    private val shizukuBridge: ShizukuBridge = ShizukuBridge()
) {

    companion object {
        private const val TAG = "SystemSettingHandler"
    }

    // ── Torch / Flashlight ──────────────────────────────────────────

    fun getTorchState(): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        return try {
            cameraManager.cameraIdList.any { id ->
                val session = try {
                    // Check if torch is on by attempting to get state
                    // CameraManager doesn't have a direct isTorchModeOn API
                    // We track state externally; default to false
                    null
                } catch (e: Exception) { null }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTorchState failed", e)
            false
        }
    }

    fun setTorch(on: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, on)
                Log.d(TAG, "Torch set to $on")
                true
            } else {
                Log.w(TAG, "No flashlight available")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "setTorch failed", e)
            false
        }
    }

    // ── Volume ──────────────────────────────────────────────────────

    fun getCurrentVolume(streamType: Int = AudioManager.STREAM_MUSIC): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getStreamVolume(streamType)
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentVolume failed", e)
            0
        }
    }

    fun getMaxVolume(streamType: Int = AudioManager.STREAM_MUSIC): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getStreamMaxVolume(streamType)
        } catch (e: Exception) {
            Log.e(TAG, "getMaxVolume failed", e)
            15
        }
    }

    fun setVolume(streamType: Int = AudioManager.STREAM_MUSIC, value: Int): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            val clamped = value.coerceIn(0, maxVolume)
            audioManager.setStreamVolume(streamType, clamped, 0)
            Log.d(TAG, "Volume set to $clamped (max: $maxVolume)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setVolume failed", e)
            false
        }
    }

    fun adjustVolumeUp(streamType: Int = AudioManager.STREAM_MUSIC): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            Log.e(TAG, "adjustVolumeUp failed", e)
            false
        }
    }

    fun adjustVolumeDown(streamType: Int = AudioManager.STREAM_MUSIC): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            Log.e(TAG, "adjustVolumeDown failed", e)
            false
        }
    }

    // ── Brightness ──────────────────────────────────────────────────

    fun getCurrentBrightness(): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (e: Settings.SettingNotFoundException) {
            Log.e(TAG, "Brightness setting not found", e)
            128
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentBrightness failed", e)
            128
        }
    }

    fun setBrightness(value: Int): Boolean {
        val clamped = value.coerceIn(0, 255)
        return try {
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    clamped
                )
                Log.d(TAG, "Brightness set to $clamped")
                true
            } else {
                Log.w(TAG, "WRITE_SETTINGS permission not granted")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "setBrightness failed", e)
            false
        }
    }

    fun setBrightnessViaShizuku(value: Int): Boolean {
        val clamped = value.coerceIn(0, 255)
        return try {
            kotlinx.coroutines.runBlocking {
                shizukuBridge.executeBlocking("settings put system screen_brightness $clamped")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "setBrightnessViaShizuku failed", e)
            false
        }
    }

    // ── WiFi State ──────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    fun getWifiState(): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                wifiManager.isWifiEnabled
            } else {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                val networkInfo = connectivityManager?.activeNetworkInfo
                networkInfo?.type == android.net.ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "getWifiState failed", e)
            false
        }
    }

    // ── Battery ─────────────────────────────────────────────────────

    fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.e(TAG, "getBatteryLevel failed", e)
            -1
        }
    }

    fun isCharging(): Boolean {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
        } catch (e: Exception) {
            // Fallback via sticky intent
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    // ── DND (Do Not Disturb) ────────────────────────────────────────

    fun isDndEnabled(): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (e: Exception) {
            Log.e(TAG, "isDndEnabled failed", e)
            false
        }
    }

    fun setDndMode(filter: Int): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.setInterruptionFilter(filter)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setDndMode failed", e)
            false
        }
    }
}
