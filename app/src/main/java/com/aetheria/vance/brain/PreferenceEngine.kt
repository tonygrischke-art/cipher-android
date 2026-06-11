package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Tier B — Preference Vector Evolution.
 *
 * Maintains a 256-dim preference vector that drifts toward embeddings of
 * responses Tony approved and away from responses he rejected.
 * Scores candidate responses at inference time — genuine learned taste
 * with zero model training.
 */
class PreferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "PreferenceEngine"
        private const val PREFS_NAME = "vance_preference"
        private const val DIMS = 256
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Load preference vector from SharedPreferences
    fun load(): FloatArray {
        val encoded = prefs.getString("pref_vector", null)
            ?: return FloatArray(DIMS) { 0.01f }
        return try {
            Base64.decode(encoded, Base64.DEFAULT).toFloatArray()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode preference vector, using defaults")
            FloatArray(DIMS) { 0.01f }
        }
    }

    // Save preference vector
    private fun save(vec: FloatArray) {
        val encoded = Base64.encodeToString(vec.toByteArray(), Base64.DEFAULT)
        prefs.edit().putString("pref_vector", encoded).apply()
    }

    // Positive feedback: nudge vector toward this response
    fun reinforce(responseText: String, strength: Float = 0.1f) {
        val vec = load()
        val responseEmbed = MemoryEmbedder.hashEmbed(responseText)
        for (i in vec.indices) {
            vec[i] = vec[i] * (1f - strength) + responseEmbed[i] * strength
        }
        normalize(vec)
        save(vec)
        Log.i(TAG, "Reinforced. Drift: +$strength")
    }

    // Negative feedback: nudge vector away from this response
    fun penalize(responseText: String, strength: Float = 0.08f) {
        val vec = load()
        val responseEmbed = MemoryEmbedder.hashEmbed(responseText)
        for (i in vec.indices) {
            vec[i] = vec[i] + strength * (vec[i] - responseEmbed[i])
        }
        normalize(vec)
        save(vec)
        Log.i(TAG, "Penalized. Drift: -$strength")
    }

    // Score a candidate response (-1..1)
    fun score(responseText: String): Float {
        val vec = load()
        val embed = MemoryEmbedder.hashEmbed(responseText)
        return MemoryEmbedder.cosineSimilarity(vec, embed)
    }

    // Pick best of N candidates
    fun selectBest(candidates: List<String>): String {
        return candidates.maxByOrNull { score(it) } ?: candidates.first()
    }

    private fun normalize(vec: FloatArray) {
        val norm = sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) for (i in vec.indices) vec[i] /= norm
    }

    private fun FloatArray.toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(this)
        return buf.array()
    }
}
