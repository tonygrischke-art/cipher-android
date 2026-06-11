package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import kotlin.math.sqrt

object MemoryEmbedder {
    private const val TAG = "MemoryEmbedder"
    const val DIMS = 256

    // Try ML Kit via reflection (no compile-time dep)
    private var mlKitClient: Any? = null
    private var mlKitGetEmbedding: java.lang.reflect.Method? = null

    fun initialize(context: Context) {
        try {
            val clazz = Class.forName("com.google.mlkit.nl.smartreply.TextEmbedding")
            mlKitClient = clazz.getMethod("getClient").invoke(null)
            // Try to resolve getEmbedding method for later use
            try {
                val getEmbText = mlKitClient!!::class.java.getMethod("getEmbedding", String::class.java)
                mlKitGetEmbedding = getEmbText
            } catch (_: Exception) {
                // Method name may differ by version — hash fallback handles it
            }
            Log.i(TAG, "ML Kit TextEmbedding available")
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit TextEmbedding not available, using hash fallback")
            mlKitClient = null
            mlKitGetEmbedding = null
        }
    }

    // Always-available hash embedding (no dependencies)
    fun hashEmbed(text: String): FloatArray {
        val vec = FloatArray(DIMS)
        val words = text.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return vec

        for (word in words) {
            val h = word.hashCode()
            val idx = Math.floorMod(h, DIMS)
            vec[idx] += 1f
            // bigram overlap
            val idx2 = Math.floorMod(h * 31L, DIMS)
            vec[idx2] += 0.5f
        }
        normalize(vec)
        return vec
    }

    suspend fun embed(text: String): FloatArray {
        // Try ML Kit first if available
        if (mlKitClient != null && mlKitGetEmbedding != null) {
            try {
                val result = mlKitGetEmbedding!!.invoke(mlKitClient, text)
                if (result is FloatArray && result.isNotEmpty()) {
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit embed failed, falling back to hash: ${e.message}")
            }
        }
        // Hash fallback — always works
        return hashEmbed(text)
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        return if (na == 0f || nb == 0f) 0f
        else dot / (sqrt(na.toDouble()) * sqrt(nb.toDouble())).toFloat()
    }

    private fun normalize(vec: FloatArray) {
        val norm = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) for (i in vec.indices) vec[i] /= norm
    }
}
