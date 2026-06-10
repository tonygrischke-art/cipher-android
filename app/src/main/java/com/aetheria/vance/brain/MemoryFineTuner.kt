package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import com.aetheria.vance.context.MemoryDao
import com.aetheria.vance.context.MemoryEntity
import com.aetheria.vance.context.LoraCheckpointDao
import com.aetheria.vance.context.LoraCheckpointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemoryFineTuner(
    private val memoryDao: MemoryDao,
    private val loraCheckpointDao: LoraCheckpointDao,
    private val context: Context
) {

    companion object {
        private const val TAG = "MemoryFineTuner"
        const val DEFAULT_LIMIT = 1000
    }

    suspend fun buildTrainingSet(limit: Int = DEFAULT_LIMIT): List<MemoryEntity> {
        return withContext(Dispatchers.IO) {
            val allSamples = memoryDao.getTrainingSamples(limit)
            if (allSamples.isEmpty()) {
                Log.w(TAG, "No training samples with non-zero reinforcement score")
                return@withContext emptyList()
            }

            val positive = allSamples.filter { it.reinforcementScore > 0 }
            val negative = allSamples.filter { it.reinforcementScore < 0 }

            Log.d(TAG, "Training set: ${positive.size} positive, ${negative.size} negative samples")

            // Stratify: 60% positive, 40% negative
            val targetPositive = (limit * 0.6).toInt()
            val targetNegative = limit - targetPositive

            val selectedPositive = positive.take(targetPositive)
            val selectedNegative = negative.take(targetNegative)

            (selectedPositive + selectedNegative).shuffled()
        }
    }

    suspend fun fineTuneLoRA(): String? {
        return withContext(Dispatchers.Default) {
            val trainingSet = buildTrainingSet()
            if (trainingSet.isEmpty()) {
                Log.w(TAG, "LoRA training skipped: empty training set")
                return@withContext null
            }

            Log.i(TAG, "LoRA training started with ${trainingSet.size} samples")
            // STUB: Wire to llama.cpp JNI in PR #8
            // val checkpointPath = llamaTrainOnSamples(trainingSet)

            val checkpointPath = "${context.filesDir.absolutePath}/vance_lora_${System.currentTimeMillis()}.weights"
            Log.i(TAG, "LoRA training complete (stub), checkpoint: $checkpointPath")
            checkpointPath
        }
    }

    suspend fun validateLoRA(checkpointPath: String): Boolean {
        return withContext(Dispatchers.Default) {
            // STUB: Implement hold-out validation in PR #8
            Log.d(TAG, "Validating LoRA checkpoint: $checkpointPath")
            true
        }
    }

    suspend fun swapActiveLoRA(checkpointId: Int) {
        withContext(Dispatchers.IO) {
            loraCheckpointDao.deactivateAll()
            loraCheckpointDao.activateCheckpoint(checkpointId)
            Log.i(TAG, "Swapped active LoRA to checkpoint $checkpointId")
        }
    }

    suspend fun enqueueSample(prompt: String, response: String, score: Int) {
        withContext(Dispatchers.IO) {
            val entity = MemoryEntity(
                prompt = prompt,
                response = response,
                reinforcementScore = score,
                source = "user"
            )
            val id = memoryDao.insert(entity)
            Log.d(TAG, "Enqueued memory sample id=$id score=$score")
        }
    }

    suspend fun saveLoRACheckpoint(filename: String, validationLoss: Double): Long {
        return withContext(Dispatchers.IO) {
            val entity = LoraCheckpointEntity(
                filename = filename,
                validationLoss = validationLoss,
                isActive = false
            )
            loraCheckpointDao.insert(entity)
        }
    }

    suspend fun getActiveCheckpointPath(): String? {
        return withContext(Dispatchers.IO) {
            loraCheckpointDao.getActiveCheckpoint()?.filename
        }
    }

    suspend fun getAllCheckpoints(): List<LoraCheckpointEntity> {
        return withContext(Dispatchers.IO) {
            loraCheckpointDao.getAllCheckpoints()
        }
    }
}