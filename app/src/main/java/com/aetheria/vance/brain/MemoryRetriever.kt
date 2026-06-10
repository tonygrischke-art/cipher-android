package com.aetheria.vance.brain

import android.util.Log
import com.aetheria.vance.context.MemoryEmbeddingDao
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * RAG memory retrieval — finds relevant past conversations.
 */
class MemoryRetriever(private val embeddingDao: MemoryEmbeddingDao) {

    companion object {
        private const val TAG = "MemoryRetriever"
    }

    suspend fun retrieve(query: String, topK: Int = 3): List<String> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val queryEmbedding = MemoryEmbedder.embed(query)
            val all = embeddingDao.getAllEmbeddings()
            if (all.isEmpty()) return@withContext emptyList()

            all.map { mem ->
                val emb = bytesToFloats(mem.embeddingBlob)
                val score = MemoryEmbedder.cosineSimilarity(queryEmbedding, emb)
                Pair(score, mem.inputText)
            }
            .sortedByDescending { it.first }
            .take(topK)
            .filter { it.first > 0.1f } // minimum similarity threshold
            .map { "[Memory] ${it.second}" }
            .also {
                Log.d(TAG, "Injecting ${it.size} memories for: ${query.take(50)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Retrieval failed: ${e.message}")
            emptyList()
        }
    }
}

fun floatsToBytes(floats: FloatArray): ByteArray {
    val bytes = ByteArray(floats.size * 4)
    for (i in floats.indices) {
        val bits = floats[i].toRawBits()
        bytes[i * 4] = (bits and 0xFF).toByte()
        bytes[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
        bytes[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
        bytes[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
    }
    return bytes
}

fun bytesToFloats(bytes: ByteArray): FloatArray {
    val floats = FloatArray(bytes.size / 4)
    for (i in floats.indices) {
        val b0 = bytes[i * 4].toInt() and 0xFF
        val b1 = bytes[i * 4 + 1].toInt() and 0xFF
        val b2 = bytes[i * 4 + 2].toInt() and 0xFF
        val b3 = bytes[i * 4 + 3].toInt() and 0xFF
        val bits = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        floats[i] = Float.fromBits(bits)
    }
    return floats
}
