package com.aetheria.vance.brain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fast LLM client — bridges to llama-server at http://127.0.0.1:8080 (Qwen2.5-0.5B).
 * For simple queries only: battery, time, wifi, status checks.
 * Short timeout, low max_tokens.
 */
class FastLlmClient(
    private val baseUrl: String = "http://127.0.0.1:8080",
    private val systemPrompt: String = "Answer in one sentence maximum."
) {
    companion object {
        private const val TAG = "FastLlm"
        private const val TIMEOUT_S = 15L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun complete(prompt: String): String? = withTimeoutOrNull(TIMEOUT_S * 1000) {
        withContext(Dispatchers.IO) {
            try {
                val messages = JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                }
                val body = JSONObject().apply {
                    put("model", "local")
                    put("messages", messages)
                    put("max_tokens", 128)
                    put("temperature", 0.5)
                    put("stream", false)
                }
                val request = Request.Builder()
                    .url("$baseUrl/v1/chat/completions")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code}")
                    return@withContext null
                }
                val json = JSONObject(response.body?.string() ?: "")
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                Log.i(TAG, "Response received (${content.length} chars)")
                content
            } catch (e: java.net.ConnectException) {
                Log.w(TAG, "Server not running at $baseUrl")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Complete failed: ${e.message}")
                null
            }
        }
    }

    fun isServerRunning(): Boolean {
        return try {
            val request = Request.Builder().url("$baseUrl/health").get().build()
            client.newCall(request).execute().isSuccessful.also {
                Log.i(TAG, if (it) "Server reachable" else "Server not reachable")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Server not running: ${e.message}")
            false
        }
    }
}
