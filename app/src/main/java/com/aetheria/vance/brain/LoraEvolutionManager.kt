package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import com.aetheria.vance.shizuku.ShizukuBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tier C — LoRA Adapter Merging (CPU Tiers Only).
 *
 * Manages a population of LoRA adapters with fitness scores.
 * Runs crossover via llama-export-lora in Termux through Shizuku/rish.
 * Genetic algorithm: weighted blend of two parent adapters = child model.
 */
class LoraEvolutionManager(
    private val context: Context,
    private val shizukuBridge: ShizukuBridge
) {
    data class LoraAdapter(
        val id: String,
        val filePath: String,
        val fitnessScore: Float = 0.5f,
        val generation: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "LoraEvolution"
        private const val PREFS_NAME = "lora_evolution"
        private const val LORA_DIR = "/data/local/tmp/vance/loras"
        private const val BASE_MODEL = "/data/local/tmp/cipher_models/qwen2.5-1_5b-instruct-q4_k_m.gguf"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Update fitness from reinforcement feedback
    fun updateFitness(adapterId: String, delta: Float) {
        val current = prefs.getFloat("fitness_$adapterId", 0.5f)
        val updated = (current + delta * 0.05f).coerceIn(0f, 1f)
        prefs.edit().putFloat("fitness_$adapterId", updated).apply()
        Log.i(TAG, "Adapter $adapterId fitness: $current → $updated")
    }

    // Get currently active adapter path (highest fitness)
    fun getActiveAdapterPath(): String? {
        val adapters = listAdapters()
        if (adapters.isEmpty()) return null
        return adapters.maxByOrNull {
            prefs.getFloat("fitness_${it.id}", 0.5f)
        }?.filePath
    }

    // List all available adapters
    fun listAdapters(): List<LoraAdapter> {
        val dir = File(LORA_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "bin" || f.extension == "gguf" }
            ?.map { f ->
                LoraAdapter(
                    id = f.nameWithoutExtension,
                    filePath = f.absolutePath,
                    fitnessScore = prefs.getFloat("fitness_${f.nameWithoutExtension}", 0.5f)
                )
            } ?: emptyList()
    }

    // Trigger crossover in Termux via Shizuku
    // Returns path to new merged adapter, or null on failure
    suspend fun crossoverTopTwo(): String? {
        val adapters = listAdapters().sortedByDescending { it.fitnessScore }
        if (adapters.size < 2) return null

        val parentA = adapters[0]
        val parentB = adapters[1]
        val totalFitness = parentA.fitnessScore + parentB.fitnessScore
        if (totalFitness <= 0f) return null

        val weightA = parentA.fitnessScore / totalFitness
        val weightB = parentB.fitnessScore / totalFitness
        val childName = "vance_gen${System.currentTimeMillis()}"
        val childPath = "$LORA_DIR/$childName.gguf"

        // Run llama-export-lora in Termux via Shizuku rish
        val cmd = buildString {
            append("cd /data/data/com.termux/files/home && ")
            append("./llama-export-lora ")
            append("--model $BASE_MODEL ")
            append("--lora-scaled ${parentA.filePath} $weightA ")
            append("--lora-scaled ${parentB.filePath} $weightB ")
            append("-o $childPath")
        }

        val output = shizukuBridge.executeBlocking(cmd, timeoutMs = 120_000L)
        Log.d(TAG, "Crossover output: $output")

        return if (File(childPath).exists()) {
            Log.i(TAG, "Crossover SUCCESS: $childName (A=$weightA, B=$weightB)")
            // Register child with neutral starting fitness
            prefs.edit().putFloat("fitness_$childName", (weightA + weightB) / 2f).apply()
            childPath
        } else {
            Log.e(TAG, "Crossover FAILED: $output")
            null
        }
    }

    // Prune worst performers, keep top 5
    fun prunePopulation() {
        val adapters = listAdapters().sortedByDescending { it.fitnessScore }
        adapters.drop(5).forEach { adapter ->
            try {
                val deleted = File(adapter.filePath).delete()
                if (deleted) {
                    prefs.edit().remove("fitness_${adapter.id}").apply()
                    Log.d(TAG, "Pruned adapter: ${adapter.id}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prune ${adapter.id}: ${e.message}")
            }
        }
    }
}
