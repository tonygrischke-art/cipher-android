package com.aetheria.vance

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class VanceApplication : Application() {

    companion object {
        private const val COPY_FLAG = ".models_copied"
    }

    override fun onCreate() {
        super.onCreate()
        val flagFile = File(filesDir, COPY_FLAG)
        if (!flagFile.exists()) {
            Log.i("VanceApplication", "Models not copied yet — starting background copy")
            Thread {
                try {
                    val srcDir = File("/data/local/tmp/cipher_models/")
                    if (!srcDir.exists()) {
                        Log.w("VanceApplication", "Model source dir missing")
                        return@Thread
                    }
                    val files = srcDir.listFiles() ?: return@Thread
                    for (src in files) {
                        val dst = File(filesDir, src.name)
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
                    flagFile.writeText(System.currentTimeMillis().toString())
                    Log.i("VanceApplication", "Model copy complete")
                } catch (e: Exception) {
                    Log.e("VanceApplication", "Model copy error: ${e.message}")
                }
            }.start()
        } else {
            Log.i("VanceApplication", "Models already copied (flag exists)")
        }
    }
}
