package com.aetheria.vance.brain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cloud fallback client for Groq API (OpenAI-compatible).
 *
 * Uses llama-3.3-70b-versatile by default.
 * API key from EncryptedSharedPreferences (key: "groq_api_key").
 *
 * Call [complete] for non-streaming suspend call.
 */
class GroqClient(
    private val context: Context? = null,
    private val apiKey: String = "",
    private val baseUrl: String = "https://api.groq.com/openai/v1"
) {
    companion object {
        private const val TAG = "GroqClient"
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
        private const val TIMEOUT_SECONDS = 30L

        const val CIPHER_SYSTEM_PROMPT = "You are Cipher, an autonomous AI agent running on an Android device. " +
            "You have access to shell commands via Shizuku, can interact with any app via Accessibility Service, " +
            "and can read/send messages. Be concise. When executing actions respond with JSON action blocks."
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Non-streaming completion. Returns the response text.
     * Throws [GroqException] on HTTP error.
     */
    suspend fun complete(
        prompt: String,
        model: String = DEFAULT_MODEL,
        systemPrompt: String = CIPHER_SYSTEM_PROMPT
    ): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Groq complete: model=$model, prompt=${prompt.length}chars")

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 1024)
            put("temperature", 0.7)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer ${resolveApiKey()}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "no body"
            Log.e(TAG, "Groq HTTP ${response.code}: $errorBody")
            throw GroqException(response.code, "HTTP ${response.code}: $errorBody")
        }

        parseResponse(response)
    }

    /**
     * Streaming completion via OkHttp enqueue. Tokens delivered to [onToken].
     * [onComplete] called when stream ends. [onError] on failure.
     */
    fun stream(
        prompt: String,
        model: String = DEFAULT_MODEL,
        systemPrompt: String = CIPHER_SYSTEM_PROMPT,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: ((String) -> Unit)? = null
    ): Call {
        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 1024)
            put("temperature", 0.7)
            put("stream", true)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer ${resolveApiKey()}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Groq stream failed", e)
                ?: e.message ?: "Unknown error"
                onComplete()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val err = "HTTP ${response.code}"
                        Log.e(TAG, "Groq stream $err")
                        onComplete()
                        return
                    }

                    response.body?.source()?.let { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data: ")) {
                                val data = line.removePrefix("data: ").trim()
                                if (data == "[DONE]") { onComplete(); return }
                                try {
                                    val json = JSONObject(data)
                                    val delta = json.getJSONArray("choices")
                                        .getJSONObject(0)
                                        .optJSONObject("delta")
                                    val content = delta?.optString("content", "") ?: ""
                                    if (content.isNotEmpty()) onToken(content)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Groq stream parse error", e)
                }
                onComplete()
            }
        })
        return call
    }

    private fun parseResponse(response: Response): String {
        return try {
            val json = JSONObject(response.body?.string() ?: "")
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Groq response", e)
            "Sorry, I couldn't understand the cloud response."
        }
    }

    private fun resolveApiKey(): String {
        if (apiKey.isNotBlank()) return apiKey
        // Load from SharedPreferences
        context?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences("cipher_secure_prefs", Context.MODE_PRIVATE)
                val key = prefs.getString("groq_api_key", "") ?: ""
                if (key.isNotBlank()) return key
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load Groq key from prefs: ${e.message}")
            }
        }
        Log.w(TAG, "Groq disabled — no API key set.")
        return ""
    }

    class GroqException(val code: Int, override val message: String) :
        Exception("Groq API error ($code): $message")
}
