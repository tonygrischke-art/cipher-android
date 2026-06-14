package com.aetheria.vance.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VanceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VanceAccessibilityService"
        const val ACTION_FOREGROUND_APP_CHANGED = "com.aetheria.vance.FOREGROUND_APP_CHANGED"
        const val EXTRA_PACKAGE_NAME = "package_name"

        @JvmField
        var instance: VanceAccessibilityService? = null
    }

    private var currentForegroundPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        Log.d(TAG, "VanceAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                if (packageName != currentForegroundPackage) {
                    currentForegroundPackage = packageName
                    Log.d(TAG, "Foreground app changed: $packageName")
                    sendBroadcast(Intent(ACTION_FOREGROUND_APP_CHANGED).apply {
                        putExtra(EXTRA_PACKAGE_NAME, packageName)
                        setPackage(packageName)
                    })
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Track content changes for context
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d(TAG, "View clicked: ${event.className}")
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                Log.d(TAG, "View focused: ${event.className}")
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.d(TAG, "Service unbound")
        return super.onUnbind(intent)
    }

    // ── Screen Reading ──────────────────────────────────────────────

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "No screen content available."
        return extractTextFromNode(root)
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append(" ")
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append(" ")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                sb.append(extractTextFromNode(child))
            }
        }
        return sb.toString().trim()
    }

    // ── Node Finding ────────────────────────────────────────────────

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeByTextRecursive(root, text)
    }

    private fun findNodeByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        if (node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodeByTextRecursive(child, text)?.let { return it }
            }
        }
        return null
    }

    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes.firstOrNull()
    }

    // ── Actions ─────────────────────────────────────────────────────

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            true
        } catch (e: Exception) {
            Log.e(TAG, "tapNode failed", e)
            false
        }
    }

    fun tapByText(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        return tapNode(node)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // Find focused node
        val focusedNode = findFocusedNode(root) ?: return false
        return try {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            Log.e(TAG, "typeText failed", e)
            false
        }
    }

    private fun findFocusedNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isFocused) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findFocusedNode(child)?.let { return it }
            }
        }
        return null
    }

    // ── Scrolling ───────────────────────────────────────────────────

    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(root) ?: return false
        return scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(root) ?: return false
        return scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findScrollableNode(child)?.let { return it }
            }
        }
        return null
    }

    // ── Gestures ────────────────────────────────────────────────────

    fun swipe(direction: String): Boolean {
        return try {
            val startX = 500f
            val startY = 1000f
            val endX: Float
            val endY: Float
            when (direction.lowercase()) {
                "up" -> { endX = startX; endY = 200f }
                "down" -> { endX = startX; endY = 1800f }
                "left" -> { endX = 100f; endY = startY }
                "right" -> { endX = 900f; endY = startY }
                else -> return false
            }
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        path, 0, 300
                    )
                )
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "swipe failed", e)
            false
        }
    }

    // ── Screenshot ────────────────────────────────────────────────

    fun takeScreenshotDescription(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val outputPath = "${android.os.Environment.getExternalStorageDirectory().absolutePath}/cipher_screenshot.png"
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            try {
                                val bitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                bitmap?.let {
                                    File(outputPath).outputStream().use { out ->
                                        it.compress(Bitmap.CompressFormat.PNG, 90, out)
                                    }
                                    Log.d(TAG, "Screenshot saved: $outputPath")
                                }
                                screenshot.hardwareBuffer.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Screenshot save failed", e)
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed: errorCode=$errorCode")
                        }
                    }
                )
                "Screenshot saved to $outputPath"
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot failed", e)
                "Screenshot failed: ${e.message}"
            }
        }
        return "Screenshot requires API 30+"
    }

    // ── Utility ─────────────────────────────────────────────────────

    fun getCurrentAppPackage(): String = currentForegroundPackage

    fun isServiceEnabled(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = enabledServices.split(':')
            colonSplitter.any { service ->
                service.contains(packageName) && service.contains("VanceAccessibilityService")
            }
        } catch (e: Exception) {
            Log.e(TAG, "isServiceEnabled check failed", e)
            false
        }
    }
}