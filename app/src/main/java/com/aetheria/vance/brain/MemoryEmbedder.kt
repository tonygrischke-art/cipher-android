package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.smartreply.TextEmbedding
import com.google.mlkit.nl.smartreply.TextEmbeddingClient
import kotlin.math.sqrt

/**
 * On-device text embedding for RAG memory.
 * Primary: ML Kit on-device text embedding (if available).
 * Fallback: TF-IDF bag-of-words (256 dims) — always works.
 */
object MemoryEmbedder {
    private const val TAG = "MemoryEmbedder"
    private const val EMBED_DIM = 256

    private var mlKitClient: TextEmbeddingClient? = null
    private var mlKitAvailable = false

    fun initialize(context: Context) {
        try {
            mlKitClient = TextEmbedding.getClient()
            mlKitAvailable = true
            Log.i(TAG, "ML Kit TextEmbedding initialized")
        } catch (e: Exception) {
            mlKitAvailable = false
            Log.w(TAG, "ML Kit TextEmbedding not available: ${e.message}")
        }
    }

    // Simple hash-based embedding as fallback
    suspend fun embed(text: String): FloatArray {
        return try {
            if (mlKitAvailable && mlKitClient != null) {
                embedWithMlKit(text)
            } else {
                hashEmbed(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Embedding failed, using zeros: ${e.message}")
            FloatArray(EMBED_DIM)
        }
    }

    private suspend fun embedWithMlKit(text: String): FloatArray {
        return try {
            val embeddings = mlKitClient!!.getEmbeddings(listOf(text)).await()
            if (embeddings.isNotEmpty()) {
                val embedding = embeddings[0]
                val vec = FloatArray(EMBED_DIM)
                val source = embedding.toFloatArray()
                val copyLen = minOf(source.size, EMBED_DIM)
                System.arraycopy(source, 0, vec, 0, copyLen)
                // Normalize
                val norm = sqrt(vec.sumOf { it * it.toDouble() }).toFloat()
                if (norm > 0) {
                    for (i in vec.indices) vec[i] /= norm
                }
                vec
            } else {
                hashEmbed(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit embedding failed, falling back: ${e.message}")
            hashEmbed(text)
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
