 1|package com.aetheria.vance.brain
 2|
 3|import android.content.Context
 4|import android.util.Log
 5|import kotlinx.coroutines.Dispatchers
 6|import kotlinx.coroutines.withContext
 7|
 8|/**
 9| * NPU inference engine using MediaPipe GenAI (tasks-genai) only.
10| * LiteRT 2.x is removed from compile-time dependencies.
11| * NPU activation is entirely runtime-only via reflection.
12| * Build passes with zero LiteRT dependencies.
13| */
14|class NpuEngine(private val context: Context) {
15|
16| companion object {
17| private const val TAG = "NpuEngine"
18| // Target model & tokenizer paths in app files dir
19| const val MODEL_FILENAME = "qwen15_abliterated_int4.tflite"
20| const val TOKENIZER_FILENAME = "qwen15_abliterated_tokenizer.model"
21| }
22|
23| private var llmInference: Any? = null
24| private var modelPath: String = ""
25| var isInitialised = false
26| private set
27|
28| fun init(modelPath: String) {
29| this.modelPath = modelPath
30| Log.i(TAG, "Init from: $modelPath")
31|
32| // Only try MediaPipe GenAI — no LiteRT 2.x at compile time
33| try {
34| val llmInferenceClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
35| val optionsClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference$LlmInferenceOptions")
36| val builderClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference$LlmInferenceOptions$Builder")
37|
38| val builder = builderClass.getDeclaredConstructor().newInstance()
39| builderClass.getMethod("setModelPath", String::class.java).invoke(builder, modelPath)
40| builderClass.getMethod("setMaxTokens", Int::class.java).invoke(builder, 512)
41| builderClass.getMethod("setTopK", Int::class.java).invoke(builder, 40)
42| builderClass.getMethod("setTemperature", Float::class.java).invoke(builder, 0.8f)
43| builderClass.getMethod("setRandomSeed", Long::class.java).invoke(builder, 42L)
44|
45| val options = builderClass.getMethod("build").invoke(builder)
46| llmInference = llmInferenceClass.getMethod("createFromOptions", Context::class.java, optionsClass).invoke(null, context, options)
47|
48| isInitialised = true
49| Log.i(TAG, "MediaPipe GenAI LlmInference SUCCESS with model: $modelPath")
50| return
51| } catch (e: ClassNotFoundException) {
52| Log.w(TAG, "MediaPipe GenAI not available")
53| } catch (e: Exception) {
54| Log.w(TAG, "MediaPipe GenAI failed: ${e.javaClass.simpleName}: ${e.message}")
55| }
56|
57| Log.e(TAG, "No inference engine available for: $modelPath")
58| isInitialised = false
59| }
60|
61| suspend fun generate(prompt: String): String? {
62| if (!isInitialised || llmInference == null) return null
63| return withContext(Dispatchers.Default) {
64| try {
65| val response = try {
66| llmInference!!.javaClass.getMethod("generateResponse", String::class.java)
67| .invoke(llmInference, prompt) as String
68| } catch (e: NoSuchMethodException) {
69| Log.w(TAG, "generateResponse not found, inference unavailable")
70| null
71| }
72| Log.i(TAG, "Inference complete (${response?.length ?: 0} chars)")
73| response
74| } catch (e: Exception) {
75| Log.e(TAG, "generate() failed: ${e.message}", e)
76| null
77| }
78| }
79| }
80|
81| fun close() {
82| try {
83| llmInference?.javaClass?.getMethod("close")?.invoke(llmInference)
84| } catch (_: Exception) {}
85| llmInference = null
86| isInitialised = false
87| }
88|}
