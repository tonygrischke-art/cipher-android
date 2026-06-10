package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NPU inference engine using LiteRT 2.x CompiledModel API.
 * Falls back to GPU, then CPU if NPU unavailable.
 */
class NpuEngine(private val context: Context) {

    companion object {
        private const val TAG = "NpuEngine"
    }

    private var compiledModel: Any? = null // CompiledModel — loaded reflectively to avoid compile-time dependency
    private var modelPath: String = ""
    var isInitialised = false
        private set

    fun init(modelPath: String) {
        this.modelPath = modelPath
        Log.i(TAG, "Init from: $modelPath")

        // Try NPU first, then GPU, then CPU
        val accelerators = listOf("NPU", "GPU", "CPU")
        for (accel in accelerators) {
            try {
                // Use reflective access to LiteRT 2.x CompiledModel
                // This avoids compile-time dependency on litert:2.1.0
                //which may not be in the current build
                val optionsClass = try {
                    Class.forName("com.google.ai.edge.litert.CompiledModel\$Options")
                } catch (e: ClassNotFoundException) {
                    Log.w(TAG, "CompiledModel.Options not found — LiteRT 2.x not in classpath")
                    return
                }
                val createMethod = try {
                    Class.forName("com.google.ai.edge.litert.CompiledModel")
                        .getMethod("create", String::class.java, optionsClass)
                } catch (e: NoSuchMethodException) {
                    Log.w(TAG, "CompiledModel.create not found")
                    return
                }
                val optionsInstance = optionsClass
                    .getConstructor(String::class.java)
                    .newInstance(accel)
                compiledModel = createMethod.invoke(null, modelPath, optionsInstance)
                isInitialised = true
                Log.i(TAG, "CompiledModel SUCCESS with accelerator=$accel")
                return
            } catch (e: Exception) {
                Log.w(TAG, "$accel failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        Log.e(TAG, "All accelerators failed for: $modelPath")
        isInitialised = false
    }

    suspend fun generate(prompt: String): String? {
        if (!isInitialised || compiledModel == null) return null
        return withContext(Dispatchers.Default) {
            try {
                // Reflective invoke: compiledModel.run(inputBuffers, outputBuffers)
                val runMethod = compiledModel!!.javaClass.getMethod("run", Any::class.java, Any::class.java)
                val createInputBuffers = compiledModel!!.javaClass.getMethod("createInputBuffers")
                val createOutputBuffers = compiledModel!!.javaClass.getMethod("createOutputBuffers")
                val inputBuffers = createInputBuffers.invoke(compiledModel)
                val outputBuffers = createOutputBuffers.invoke(compiledModel)
                runMethod.invoke(compiledModel, inputBuffers, outputBuffers)
                Log.i(TAG, "Inference complete")
                // TODO: Extract actual text output from outputBuffers
                // For now, return null to indicate LiteRT bridge needs full implementation
                null
            } catch (e: Exception) {
                Log.e(TAG, "generate() failed: ${e.message}")
                null
            }
        }
    }

    fun close() {
        try {
            compiledModel?.javaClass?.getMethod("close")?.invoke(compiledModel)
        } catch (_: Exception) {}
        compiledModel = null
        isInitialised = false
    }
}
