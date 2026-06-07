package com.aetheria.vance

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

@HiltAndroidApp
class VanceApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.i("VanceApplication", "onCreate — starting model copy")
        copyModelsIfNeeded()
    }

    private fun copyModelsIfNeeded() {
        appScope.launch {
            try {
                val srcDir = File("/data/local/tmp/cipher_models/")
                val dstDir = filesDir
                if (!srcDir.exists()) {
                    Log.w("VanceApplication", "Model source dir missing")
                    return@launch
                }
                val files = srcDir.listFiles() ?: return@launch
                for (src in files) {
                    val dst = File(dstDir, src.name)
                    if (!dst.exists() || dst.length() != src.length()) {
                        Log.i("VanceApplication", "Copying ${src.name} (${src.length() / 1024 / 1024}MB)")
                        try {
                            src.copyTo(dst, overwrite = true)
                            Log.i("VanceApplication", "Copied: ${src.name}")
                        } catch (e: Exception) {
                            Log.e("VanceApplication", "Failed to copy ${src.name}: ${e.message}")
                        }
                    }
                }
                Log.i("VanceApplication", "Model copy complete")
            } catch (e: Exception) {
                Log.e("VanceApplication", "Model copy error: ${e.message}")
            }
        }
    }
}
