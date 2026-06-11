package com.aetheria.vance.brain

import android.util.Log
import kotlin.math.sqrt

/**
 * On-device text embedding for RAG memory + preference scoring.
 * Hash-based embedding — no ML Kit, no cloud, always available.
 * EMBED_DIM: 128 dimensions for memory similarity.
 */
object MemoryEmbedder {
    private const val TAG = "MemoryEmbedder"
    const val EMBED_DIM = 128

    fun initialize() {
        Log.i(TAG, "MemoryEmbedder initialized (hash mode)")
    }

    suspend fun embed(text: String): FloatArray {
        return try {
            hashEmbed(text)
        } catch (e: Exception) {
            Log.w(TAG, "Embedding failed, using zeros: ${e.message}")
            FloatArray(EMBED_DIM)
        }
    }

    /**
     * Hash-based embedding using word-level hashing for better distribution.
     * Each word contributes to two positions (unigram + bigram overlap).
     */
    fun hashEmbed(text: String): FloatArray {
        val vec = FloatArray(EMBED_DIM)
        val words = text.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return vec

        for (word in words) {
            val h = word.hashCode()
            val idx = Math.floorMod(h, EMBED_DIM)
            vec[idx] += 1f
            // bigram overlap — second position from different hash mix
            val idx2 = Math.floorMod(h * 31L, EMBED_DIM)
            vec[idx2] += 0.5f
        }

        normalize(vec)
        return vec
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i]*b[i]; normA += a[i]*a[i]; normB += b[i]*b[i] }
        return if (normA == 0f || normB == 0f) 0f
        else dot / (sqrt(normA.toDouble()) * sqrt(normB.toDouble())).toFloat()
    }

    private fun normalize(vec: FloatArray) {
        val norm = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) for (i in vec.indices) vec[i] /= norm
    }
}
