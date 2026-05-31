package com.aetheria.cipher.brain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class GroqClient(
    private val apiKey: String = "",
    private val baseUrl: String = "https://api.groq.com/openai/v1"
) {

    companion object {
        private const val TAG = "GroqClient"
        const val MODEL_KIMI_K2 = "kimi-k2"
        const val MODEL_QWEN3_32B = "qwen3-32b"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun complete(prompt: String, model: String = MODEL_KIMI_K2, systemPrompt: String? = null): String {
        return try {
            val messages = JSONArray()
            if (systemPrompt != null) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", 2048)
                put("temperature", 0.7)
            }

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer ${getApiKey()}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            parseResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Groq API call failed", e)
            "Sorry, I couldn't reach the cloud brain right now."
        }
    }

    fun stream(
        prompt: String,
        model: String = MODEL_KIMI_K2,
        systemPrompt: String? = null,
        onToken: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        try {
            val messages = JSONArray()
            if (systemPrompt != null) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", 2048)
                put("temperature", 0.7)
                put("stream", true)
            }

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer ${getApiKey()}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Groq stream failed", e)
                    onToken("\n[Connection error]")
                    onComplete()
                }

                override fun onResponse(call: Call, response: Response) {
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
                                        .getJSONObject("delta")
                                        .optString("content", "")
                                    if (delta.isNotEmpty()) onToken(delta)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    onComplete()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Groq stream setup failed", e)
            onToken("Sorry, streaming failed.")
            onComplete()
        }
    }

    private fun parseResponse(response: Response): String {
        if (!response.isSuccessful) {
            Log.e(TAG, "Groq API error: ${response.code}")
            return "Sorry, cloud brain returned an error."
        }
        return try {
            val json = JSONObject(response.body?.string() ?: "")
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Groq response", e)
            "Sorry, couldn't parse the cloud response."
        }
    }

    private fun getApiKey(): String {
        return apiKey.ifEmpty {
            // TODO: Load from EncryptedSharedPreferences
            ""
        }
    }
}
