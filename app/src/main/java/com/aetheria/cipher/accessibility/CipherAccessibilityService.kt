package com.aetheria.cipher.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CipherAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CipherAccessibilityService"
    }

    private var currentRootNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.d(TAG, "CipherAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: ""
                Log.d(TAG, "Window changed: $packageName/$className")
                currentRootNode = rootInActiveWindow
                onAppForegroundChanged(packageName, className)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                currentRootNode = rootInActiveWindow
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                Log.d(TAG, "Notification received from ${event.packageName}")
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    // ── Public API for action dispatcher ──

    fun findAndTap(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, text) ?: return false
        return performActionOnNode(node)
    }

    fun findAndType(fieldText: String, inputText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, fieldText) ?: return false
        val arguments = android.os.Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollableNode = findScrollableNode(root) ?: return false
        return scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "No screen content available."
        return extractTextFromNode(root)
    }

    fun takeScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // TODO: Use PixelCopy or MediaProjection for screenshot
        }
        return null
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull { it.text?.toString()?.contains(text, ignoreCase = true) == true }
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

    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            sb.append(extractTextFromNode(node.getChild(i)))
        }
        return sb.toString().trim()
    }

    private fun performActionOnNode(node: AccessibilityNodeInfo): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return true
    }

    private fun onAppForegroundChanged(packageName: String, className: String) {
        // TODO: Notify ContextEngine
        Log.d(TAG, "App foreground: $packageName/$className")
    }
}
