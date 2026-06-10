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
 * Bridges Vance to a llama-server instance at http://127.0.0.1:8080.
 * Local fallback when NPU inference is unavailable.
 */
class LocalLlmClient(
    private val baseUrl: String = "http://127.0.0.1:8080",
    private val systemPrompt: String = BrainRouter.VANCE_SYSTEM_PROMPT
) {
    companion object {
        private const val TAG = "LocalLlm"
        private const val TIMEOUT_S = 60L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
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
                    put("max_tokens", 512)
                    put("temperature", 0.7)
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
