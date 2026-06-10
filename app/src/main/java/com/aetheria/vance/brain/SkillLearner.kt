package com.aetheria.vance.brain

import android.util.Log
import com.aetheria.vance.context.MemoryStore
import com.aetheria.vance.context.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Auto skill learning — observes interactions and creates new skills from patterns.
 */
class SkillLearner(private val memoryStore: MemoryStore) {

    companion object {
        private const val TAG = "SkillLearner"
        private const val ANALYZE_INTERVAL = 10
    }

    private val buffer = ArrayDeque<Triple<String, String, Boolean>>(50)
    private var interactionCount = 0

    fun observe(query: String, response: String, success: Boolean) {
        if (buffer.size >= 50) buffer.removeFirst()
        buffer.addLast(Triple(query, response, success))
        interactionCount++
        if (interactionCount % ANALYZE_INTERVAL == 0) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { analyzePatterns() }
        }
    }

    private suspend fun analyzePatterns() = withContext(Dispatchers.IO) {
        try {
            val successful = buffer.filter { it.third }
            if (successful.size < 3) return@withContext

            // RULE 1: Repetition — same query pattern appears 3+ times
            val queryGroups = successful.groupBy { it.first.lowercase().trim() }
            for ((query, instances) in queryGroups) {
                if (instances.size >= 3) {
                    val skillId = "learned_${query.hashCode().toUInt()}"
                    val existing = memoryStore.getAllSkills()?.any { it.id == skillId } ?: false
                    if (!existing) {
                        val skill = SkillEntity(
                            id = skillId,
                            name = "learned_${query.take(20)}",
                            triggerPatterns = JSONArray(listOf(query.lowercase())).toString(),
                            responseTemplate = instances.first().second,
                            actionType = "query",
                            confidenceScore = 0.8f,
                            useCount = instances.size,
                            lastUsed = System.currentTimeMillis(),
                            autoLearned = true,
                            approved = false
                        )
                        memoryStore.insertSkills(listOf(skill))
                        Log.i(TAG, "Auto-learned skill: '${skill.name}' from ${instances.size} repetitions")
                    }
                }
            }

            // RULE 2: High-confidence patterns — successful responses with common keywords
            val keywordPatterns = mapOf(
                "battery" to "Battery is {battery}%.",
                "time" to "It's {time}.",
                "wifi" to "{wifi}.",
                "date" to "Today is {date}."
            )
            for ((keyword, template) in keywordPatterns) {
                val matching = successful.filter { it.first.lowercase().contains(keyword) }
                if (matching.size >= 5) {
                    val skillId = "learned_keyword_$keyword"
                    val existing = memoryStore.getAllSkills()?.any { it.id == skillId } ?: false
                    if (!existing) {
                        val skill = SkillEntity(
                            id = skillId,
                            name = "learned_$keyword",
                            triggerPatterns = JSONArray(listOf(keyword)).toString(),
                            responseTemplate = template,
                            actionType = "query",
                            confidenceScore = 0.7f,
                            useCount = matching.size,
                            lastUsed = System.currentTimeMillis(),
                            autoLearned = true,
                            approved = false
                        )
                        memoryStore.insertSkills(listOf(skill))
                        Log.i(TAG, "Auto-learned keyword skill: '$keyword' from ${matching.size} matches")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pattern analysis failed: ${e.message}")
        }
    }
}
