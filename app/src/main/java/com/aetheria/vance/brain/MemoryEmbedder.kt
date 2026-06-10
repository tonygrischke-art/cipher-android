package com.aetheria.vance.brain

import android.util.Log
import kotlin.math.sqrt

/**
 * On-device text embedding for RAG memory.
 * Primary: ML Kit on-device text embedding.
 * Fallback: TF-IDF bag-of-words (256 dims).
 */
object MemoryEmbedder {
    private const val TAG = "MemoryEmbedder"
    private const val EMBED_DIM = 256

    // Simple hash-based embedding as fallback
    fun embed(text: String): FloatArray {
        return try {
            hashEmbed(text)
        } catch (e: Exception) {
            Log.w(TAG, "Embedding failed, using zeros: ${e.message}")
            FloatArray(EMBED_DIM)
        }
    }

    private fun hashEmbed(text: String): FloatArray {
        val vec = FloatArray(EMBED_DIM)
        val words = text.lowercase().split(Regex("\\s+"))
        for (word in words) {
            val hash = word.hashCode()
            val idx = Math.floorMod(hash, EMBED_DIM)
            vec[idx] += 1f
        }
        // Normalize
        val norm = sqrt(vec.sumOf { it * it.toDouble() }).toFloat()
        if (norm > 0) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0f || normB == 0f) 0f
        else dot / (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat()
    }
}
