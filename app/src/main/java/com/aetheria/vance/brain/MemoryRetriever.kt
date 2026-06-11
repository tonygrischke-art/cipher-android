package com.aetheria.vance.brain

import android.util.Log
import com.aetheria.vance.context.MemoryDao
import com.aetheria.vance.context.MemoryEmbeddingDao
import com.aetheria.vance.context.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * RAG memory retrieval — finds relevant past conversations via cosine similarity.
 * Also provides high-reinforcement memories for Tier A few-shot injection.
 */
class MemoryRetriever(
    private val embeddingDao: MemoryEmbeddingDao,
    private val memoryDao: MemoryDao? = null
) {

    companion object {
        private const val TAG = "MemoryRetriever"
    }

    suspend fun retrieve(query: String, topK: Int = 3): List<String> = withContext(Dispatchers.IO) {
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
                Log.i(TAG, "RAG: injecting ${it.size} memories for: ${query.take(50)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Retrieval failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Tier A: Get high-reinforcement memories for few-shot injection.
     * Returns memories Tony approved (score > 1), sorted by relevance to query.
     */
    suspend fun getRelevantMemories(query: String, limit: Int = 3): List<MemoryEntity> = withContext(Dispatchers.IO) {
        try {
            val memoryDao = memoryDao ?: return@withContext emptyList()
            val recent = memoryDao.getTrainingSamples(200)
            if (recent.isEmpty()) return@withContext emptyList()

            val scored = recent.filter { it.reinforcementScore > 1 }
            if (scored.isEmpty()) return@withContext emptyList()

            // If no embedding filtering available, just take top by score
            scored.sortedByDescending { it.reinforcementScore }.take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "getRelevantMemories failed: ${e.message}")
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
