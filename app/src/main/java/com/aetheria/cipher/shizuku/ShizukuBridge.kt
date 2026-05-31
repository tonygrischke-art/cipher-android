package com.aetheria.cipher.shizuku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuBridge {

    companion object {
        private const val TAG = "ShizukuBridge"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    private var isShizukuAvailable: Boolean = false
    private var isShizukuPermissionGranted: Boolean = false

    fun initialize() {
        try {
            // TODO: Check Shizuku availability
            // isShizukuAvailable = Shizuku.pingBinder()
            // if (isShizukuAvailable) {
            //     isShizukuPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            // }
            Log.d(TAG, "Shizuku bridge initialized (stub)")
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku not available", e)
            isShizukuAvailable = false
        }
    }

    fun isAvailable(): Boolean = isShizukuAvailable && isShizukuPermissionGranted

    suspend fun executeCommand(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        if (!isAvailable()) {
            return "Shizuku is not available. Please start Shizuku and grant permission."
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = withTimeoutOrNull(timeoutMs) {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                val stdout = reader.readText()
                val stderr = errorReader.readText()
                process.waitFor()
                if (stderr.isNotBlank()) "Error: $stderr" else stdout.trim()
            } ?: run {
                process.destroy()
                "Command timed out after ${timeoutMs / 1000}s"
            }
            output
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed: $command", e)
            "Failed to execute: ${e.message}"
        }
    }

    fun executeCommandStreaming(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Flow<String> = flow {
        if (!isAvailable()) {
            emit("Shizuku is not available.")
            return@flow
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            reader.useLines { lines ->
                lines.forEach { line -> emit(line) }
            }

            val errors = errorReader.readText()
            if (errors.isNotBlank()) emit("\n[stderr] $errors")

            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Streaming command failed: $command", e)
            emit("Error: ${e.message}")
        }
    }
}
