package com.aetheria.cipher.actions

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aetheria.cipher.shizuku.ShizukuBridge

class SmsHandler(
    private val context: Context,
    private val shizukuBridge: ShizukuBridge = ShizukuBridge()
) {

    companion object {
        private const val TAG = "SmsHandler"
        private const val SMS_MAX_LENGTH = 160
    }

    data class SmsMessage(
        val sender: String,
        val body: String,
        val timestamp: Long,
        val type: String // "inbox" or "sent"
    )

    // ── Send SMS ────────────────────────────────────────────────────

    fun sendSms(phoneNumber: String, message: String): ActionExecutor.ActionResult {
        if (message.isBlank()) {
            return ActionExecutor.ActionResult(false, "Empty message", "What should I send?")
        }
        if (phoneNumber.isBlank()) {
            return ActionExecutor.ActionResult(false, "No phone number", "Who should I send this to?")
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return ActionExecutor.ActionResult(false, "SEND_SMS permission not granted", "I don't have permission to send texts")
        }

        return try {
            val smsManager = SmsManager.getDefault()
            if (message.length > SMS_MAX_LENGTH) {
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }
            ActionExecutor.ActionResult(true, "SMS sent to $phoneNumber", "Message sent")
        } catch (e: Exception) {
            Log.e(TAG, "sendSms failed", e)
            ActionExecutor.ActionResult(false, e.message ?: "SMS send failed", "I couldn't send that message")
        }
    }

    fun sendSmsToContact(contactName: String, message: String): ActionExecutor.ActionResult {
        val number = resolveContactNumber(contactName)
        if (number.isBlank()) {
            return ActionExecutor.ActionResult(false, "Contact not found: $contactName", "I couldn't find a number for $contactName")
        }
        val result = sendSms(number, message)
        return if (result.success) {
            result.copy(spokenResponse = "Message sent to $contactName")
        } else {
            result
        }
    }

    // ── Read SMS ────────────────────────────────────────────────────

    fun readLastSms(count: Int = 5): List<SmsMessage> {
        return querySms("content://sms/inbox", count, "inbox")
    }

    fun readSentSms(count: Int = 5): List<SmsMessage> {
        return querySms("content://sms/sent", count, "sent")
    }

    fun readConversation(address: String, count: Int = 10): List<SmsMessage> {
        return try {
            val messages = mutableListOf<SmsMessage>()
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("address", "body", "date", "type"),
                "address = ?",
                arrayOf(address),
                "date DESC LIMIT $count"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    messages.add(cursorToSmsMessage(it))
                }
            }
            messages
        } catch (e: Exception) {
            Log.e(TAG, "readConversation failed", e)
            emptyList()
        }
    }

    private fun querySms(uri: String, count: Int, type: String): List<SmsMessage> {
        return try {
            val messages = mutableListOf<SmsMessage>()
            val cursor = context.contentResolver.query(
                Uri.parse(uri),
                arrayOf("address", "body", "date"),
                null, null,
                "date DESC LIMIT $count"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    messages.add(
                        SmsMessage(
                            sender = it.getStringSafe("address") ?: "Unknown",
                            body = it.getStringSafe("body") ?: "",
                            timestamp = it.getLongSafe("date") ?: 0L,
                            type = type
                        )
                    )
                }
            }
            messages
        } catch (e: Exception) {
            Log.e(TAG, "querySms failed", e)
            emptyList()
        }
    }

    // ── Contact Resolution ──────────────────────────────────────────

    fun resolveContactNumber(name: String): String {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(0) ?: ""
                } else ""
            } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "resolveContactNumber failed", e)
            ""
        }
    }

    fun resolveContactName(number: String): String {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else ""
            } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "resolveContactName failed", e)
            ""
        }
    }

    // ── SMS Summary ─────────────────────────────────────────────────

    fun getSmsSummary(count: Int = 5): String {
        val messages = readLastSms(count)
        if (messages.isEmpty()) return "No recent messages"

        return messages.joinToString("\n") { msg ->
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date(msg.timestamp))
            "[$time] ${msg.sender}: ${msg.body.take(80)}"
        }
    }

    // ── Cursor helpers ──────────────────────────────────────────────

    private fun cursorToSmsMessage(cursor: Cursor): SmsMessage {
        return SmsMessage(
            sender = cursor.getStringSafe("address") ?: "Unknown",
            body = cursor.getStringSafe("body") ?: "",
            timestamp = cursor.getLongSafe("date") ?: 0L,
            type = "unknown"
        )
    }

    private fun Cursor.getStringSafe(column: String): String? {
        val index = getColumnIndex(column)
        return if (index != -1) getString(index) else null
    }

    private fun Cursor.getLongSafe(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index != -1) getLong(index) else null
    }

    fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.US)
        return sdf.format(java.util.Date(timestamp))
    }
}
