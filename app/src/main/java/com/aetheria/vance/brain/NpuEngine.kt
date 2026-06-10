package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NPU inference engine using LiteRT 2.x CompiledModel API with LiteRT-LM.
 * Falls back to GPU, then CPU if NPU unavailable.
 * Uses LiteRT-LM's Kotlin API for text-in/text-out generation.
 */
class NpuEngine(private val context: Context) {

    companion object {
        private const val TAG = "NpuEngine"
        // Target model & tokenizer paths in app files dir
        const val MODEL_FILENAME = "qwen15_abliterated_int4.tflite"
        const val TOKENIZER_FILENAME = "qwen15_abliterated_tokenizer.model"
    }

    private var llmInference: Any? = null
    private var modelPath: String = ""
    var isInitialised = false
        private set

    fun init(modelPath: String) {
        this.modelPath = modelPath
        Log.i(TAG, "Init from: $modelPath")

        val accelerators = listOf("NPU", "GPU", "CPU")
        for (accel in accelerators) {
            try {
                // Try LiteRT-LM LlmInference API first (MediaPipe GenAI compatible)
                val llmInferenceClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
                val optionsClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference$LlmInferenceOptions")
                val builderClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference$LlmInferenceOptions$Builder")

                val builder = builderClass.getDeclaredConstructor().newInstance()
                builderClass.getMethod("setModelPath", String::class.java).invoke(builder, modelPath)
                builderClass.getMethod("setMaxTokens", Int::class.java).invoke(builder, 512)
                builderClass.getMethod("setTopK", Int::class.java).invoke(builder, 40)
                builderClass.getMethod("setTemperature", Float::class.java).invoke(builder, 0.8f)
                builderClass.getMethod("setRandomSeed", Long::class.java).invoke(builder, 42L)

                val options = builderClass.getMethod("build").invoke(builder)
                llmInference = llmInferenceClass.getMethod("createFromOptions", Context::class.java, optionsClass).invoke(null, context, options)

                isInitialised = true
                Log.i(TAG, "LiteRT-LM LlmInference SUCCESS with model: $modelPath")
                return
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "LiteRT-LM not available, trying CompiledModel API")
            } catch (e: Exception) {
                Log.w(TAG, "LiteRT-LM failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // Fallback: LiteRT 2.x CompiledModel API
            try {
                val optionsClass = Class.forName("com.google.ai.edge.litert.CompiledModel\$Options")
                val createMethod = Class.forName("com.google.ai.edge.litert.CompiledModel")
                    .getMethod("create", String::class.java, optionsClass)
                val optionsInstance = optionsClass
                    .getConstructor(String::class.java)
                    .newInstance(accel)
                llmInference = createMethod.invoke(null, modelPath, optionsInstance)
                isInitialised = true
                Log.i(TAG, "CompiledModel SUCCESS with accelerator=$accel")
                return
            } catch (e: Exception) {
                Log.w(TAG, "$accel failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        Log.e(TAG, "All inference APIs failed for: $modelPath")
        isInitialised = false
    }

    suspend fun generate(prompt: String): String? {
        if (!isInitialised || llmInference == null) return null
        return withContext(Dispatchers.Default) {
            try {
                // Try LiteRT-LM generateResponse first
                val response = try {
                    llmInference!!.javaClass.getMethod("generateResponse", String::class.java)
                        .invoke(llmInference, prompt) as String
                } catch (e: NoSuchMethodException) {
                    // Fallback: CompiledModel run with buffers
                    Log.w(TAG, "generateResponse not found, trying buffer API")
                    val runMethod = llmInference!!.javaClass.getMethod("run", Any::class.java, Any::class.java)
                    val createInputBuffers = llmInference!!.javaClass.getMethod("createInputBuffers")
                    val createOutputBuffers = llmInference!!.javaClass.getMethod("createOutputBuffers")
                    val inputBuffers = createInputBuffers.invoke(llmInference)
                    val outputBuffers = createOutputBuffers.invoke(llmInference)
                    runMethod.invoke(llmInference, inputBuffers, outputBuffers)
                    // Try to extract text from output buffers
                    val getStringMethod = outputBuffers.javaClass.getMethod("getString")
                    getStringMethod.invoke(outputBuffers) as String
                }
                Log.i(TAG, "Inference complete (${response.length} chars)")
                response
            } catch (e: Exception) {
                Log.e(TAG, "generate() failed: ${e.message}", e)
                null
            }
        }
    }

    fun close() {
        try {
            llmInference?.javaClass?.getMethod("close")?.invoke(llmInference)
        } catch (_: Exception) {}
        llmInference = null
        isInitialised = false
    }
}
