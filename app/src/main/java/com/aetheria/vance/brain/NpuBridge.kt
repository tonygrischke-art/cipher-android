package com.aetheria.vance.brain

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NpuBridge"

@Singleton
class NpuBridge @Inject constructor() {

    /**
     * True only after [initialize] succeeds.
     * Every caller MUST check this before invoking any native method.
     * Fixes the silent-fail bug: previously isAvailable() checked `initialized`
     * (set optimistically) rather than whether the library actually loaded.
     */
    var isAvailable: Boolean = false
        private set

    /**
     * Attempt to load libvance_npu.so via the safe dlopen path in npu_loader.
     * Idempotent — safe to call multiple times.
     * Must be called before any other method on this class.
     */
    fun initialize(): Boolean {
        if (isAvailable) return true
        isAvailable = try {
            nativeInit()
        } catch (e: UnsatisfiedLinkError) {
            // npu_loader.so itself is missing from the APK — build misconfiguration.
            Log.e(TAG, "npu_loader.so not found in APK: ${e.message}")
            false
        } catch (e: Throwable) {
            Log.e(TAG, "nativeInit threw unexpectedly: ${e.message}")
            false
        }
        Log.i(TAG, "NPU initialize result: $isAvailable")
        return isAvailable
    }

    fun initNpu(): Boolean {
        if (!isAvailable) return false
        return try {
            nativeInitNpu()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeInitNpu failed: ${e.message}")
            false
        }
    }

    fun shutdownNpu() {
        if (!isAvailable) return
        try { nativeShutdownNpu() }
        catch (e: Throwable) { Log.e(TAG, "nativeShutdownNpu failed: ${e.message}") }
        try { nativeRelease() }
        catch (e: Throwable) { Log.e(TAG, "nativeRelease failed: ${e.message}") }
        isAvailable = false
    }

    fun isNpuAvailable(): Boolean {
        if (!isAvailable) return false
        return try { nativeIsNpuAvailable() }
        catch (e: Throwable) { false }
    }

    fun runInference(modelPath: String, inputText: String): String {
        check(isAvailable) { "NPU not initialized — call initialize() first" }
        return try { nativeRunInference(modelPath, inputText) }
        catch (e: Throwable) {
            Log.e(TAG, "nativeRunInference failed: ${e.message}")
            ""
        }
    }

    fun getLastError(): String {
        if (!isAvailable) return "NPU library not loaded"
        return try { nativeGetLastError() }
        catch (e: Throwable) { "getLastError failed: ${e.message}" }
    }

    // ── Native declarations ─────────────────────────────────────────────────
    // nativeInit / nativeRelease — implemented in npu_loader.cpp (always safe)
    // All others — implemented in libvance_npu.so, resolved after nativeInit()

    private external fun nativeInit(): Boolean
    private external fun nativeRelease()

    private external fun nativeInitNpu(): Boolean
    private external fun nativeShutdownNpu()
    private external fun nativeIsNpuAvailable(): Boolean
    private external fun nativeRunInference(modelPath: String, inputText: String): String
    private external fun nativeGetLastError(): String

    companion object {
        init {
            // npu_loader.so: always in APK, zero Neuron deps, never crashes.
            // libvance_npu.so is loaded lazily inside nativeInit() only.
            try {
                System.loadLibrary("npu_loader")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "FATAL: npu_loader.so missing from APK: ${e.message}")
            }
        }
    }
}
