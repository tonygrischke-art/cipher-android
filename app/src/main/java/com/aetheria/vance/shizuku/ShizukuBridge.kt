package com.aetheria.vance.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume

class ShizukuBridge {

    companion object {
        private const val TAG = "ShizukuBridge"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val BLOCKING_TIMEOUT_MS = 10_000L
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private var isShizukuBound = false
    private var permissionGranted = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.aetheria.vance", VanceUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("service")
        .debuggable(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Shizuku user service connected")
            isShizukuBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Shizuku user service disconnected")
            isShizukuBound = false
        }
    }

    private val permissionRequestListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku permission result: granted=$permissionGranted")
            pendingPermissionContinuation?.let { cont ->
                cont.resume(permissionGranted)
                pendingPermissionContinuation = null
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder died")
        isShizukuBound = false
        permissionGranted = false
    }

    private var pendingPermissionContinuation: kotlin.coroutines.Continuation<Boolean>? = null

    fun initialize() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionRequestListener)

            if (Shizuku.pingBinder()) {
                permissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                if (!permissionGranted && !Shizuku.shouldShowRequestPermissionRationale()) {
                    Log.d(TAG, "Requesting Shizuku permission")
                    requestPermission()
                } else if (!permissionGranted) {
                    Log.w(TAG, "Shizuku permission previously denied")
                }
                try {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind Shizuku user service", e)
                }
            } else {
                Log.w(TAG, "Shizuku not running — ping failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku initialization failed", e)
        }
    }

    fun destroy() {
        try {
            if (isShizukuBound) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }
            Shizuku.removeRequestPermissionResultListener(permissionRequestListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error during Shizuku cleanup", e)
        }
    }

    private fun requestPermission() {
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    suspend fun requestPermissionAsync(): Boolean {
        if (permissionGranted) return true
        if (!Shizuku.pingBinder()) return false
        return suspendCancellableCoroutine { cont ->
            pendingPermissionContinuation = cont
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            cont.invokeOnCancellation {
                pendingPermissionContinuation = null
            }
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    suspend fun executeBlocking(
        cmd: String,
        timeoutMs: Long = BLOCKING_TIMEOUT_MS
    ): String {
        if (!isAvailable()) {
            throw ShizukuNotAvailableException(
                "Shizuku is not running. Open Shizuku app and start the service."
            )
        }

        return withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(timeoutMs) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("rish", "-c", cmd))
                    val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
                    val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
                    process.waitFor()

                    if (stderr.isNotBlank()) {
                        "$stdout[ERR]$stderr".trim()
                    } else {
                        stdout.trim()
                    }
                } catch (e: Exception) {
                    "Command execution failed: ${e.message}"
                }
            }

            if (result == null) {
                throw CommandTimeoutException("Command timed out after ${timeoutMs}ms: $cmd")
            }
            result
        }
    }

    fun executeCommand(
        cmd: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Flow<String> = flow {
        if (!isAvailable()) {
            emit("Shizuku is not running. Open Shizuku app and start the service.")
            return@flow
        }

        try {
            val process = withContext(Dispatchers.IO) {
                Runtime.getRuntime().exec(arrayOf("rish", "-c", cmd))
            }

            val stdoutReader = withContext(Dispatchers.IO) {
                BufferedReader(InputStreamReader(process.inputStream))
            }
            val stderrReader = withContext(Dispatchers.IO) {
                BufferedReader(InputStreamReader(process.errorStream))
            }

            val timedOut = withTimeoutOrNull(timeoutMs) {
                try {
                    stdoutReader.useLines { lines ->
                        lines.forEach { line ->
                            emit(line)
                        }
                    }

                    val stderr = stderrReader.readText()
                    if (stderr.isNotBlank()) {
                        emit("[ERR]$stderr")
                    }

                    process.waitFor()
                } catch (e: Exception) {
                    emit("[ERR]Streaming failed: ${e.message}")
                }
            }

            if (timedOut == null) {
                process.destroy()
                emit("[ERR]Command timed out after ${timeoutMs}ms")
            }
        } catch (e: Exception) {
            emit("[ERR]Execution failed: ${e.message}")
            Log.e(TAG, "executeCommand failed: $cmd", e)
        }
    }

    fun onShizukuPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            pendingPermissionContinuation?.let { cont ->
                cont.resume(permissionGranted)
                pendingPermissionContinuation = null
            }
        }
    }
}

class ShizukuNotAvailableException(message: String) : Exception(message)
class CommandTimeoutException(message: String) : Exception(message)
