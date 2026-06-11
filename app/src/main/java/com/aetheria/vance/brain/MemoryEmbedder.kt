package com.aetheria.vance.brain

import android.util.Log
import kotlin.math.sqrt

/**
 * On-device text embedding for RAG memory.
 * Stub implementation using hash-based embedding until real model is integrated.
 * EMBED_DIM: 128 dimensions for memory similarity.
 */
object MemoryEmbedder {
    private const val TAG = "MemoryEmbedder"
    private const val EMBED_DIM = 128

    fun initialize() {
        Log.i(TAG, "MemoryEmbedder initialized (stub mode)")
    }

    suspend fun embed(text: String): FloatArray {
        return try {
            hashEmbed(text)
        } catch (e: Exception) {
            Log.w(TAG, "Embedding failed, using zeros: ${e.message}")
            FloatArray(EMBED_DIM)
        }
    }

    private fun hashEmbed(text: String): FloatArray {
        val hash = text.hashCode()
        return FloatArray(EMBED_DIM) { i -> ((hash shr (i % 32)) and 0xFF) / 255f }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i]*b[i]; normA += a[i]*a[i]; normB += b[i]*b[i] }
        return if (normA == 0f || normB == 0f) 0f else dot / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())).toFloat()
    }
}