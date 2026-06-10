package com.aetheria.vance.brain

import android.util.Log
import com.aetheria.vance.context.ContextEngine
import com.aetheria.vance.context.MemoryStore
import com.aetheria.vance.context.SkillEntity
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Matches user queries against seeded and learned skills.
 * Runs BEFORE any inference engine — zero-cost for simple queries.
 */
class SkillMatcher(private val memoryStore: MemoryStore) {

    companion object {
        private const val TAG = "SkillMatcher"
    }

    suspend fun match(query: String): SkillEntity? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val q = query.lowercase().trim()
            val skills = memoryStore.getAllSkills()?.filter { it.approved } ?: emptyList()
            skills.firstOrNull { skill ->
                try {
                    val patterns = JSONArray(skill.triggerPatterns)
                    (0 until patterns.length()).any { i ->
                        q.contains(patterns.getString(i).lowercase())
                    }
                } catch (_: Exception) { false }
            }?.also {
                Log.i(TAG, "Matched skill: '${it.name}' for query: '${query.take(40)}'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Skill matching failed: ${e.message}")
            null
        }
    }

    fun execute(skill: SkillEntity, ctx: ContextEngine.ContextSnapshot): String {
        return skill.responseTemplate
            .replace("{battery}", ctx.batteryLevel.toString())
            .replace("{time}", ctx.timeFormatted)
            .replace("{date}", ctx.timeFormatted)
            .replace("{wifi}", ctx.networkType)
            .replace("{memory}", ctx.thermalState)
            .replace("{screen}", ctx.foregroundApp)
    }

    suspend fun seedIfNeeded() = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val existing = memoryStore.getAllSkills()
            if (!existing.isNullOrEmpty()) {
                Log.d(TAG, "Skills already seeded (${existing.size})")
                return@withContext
            }
            val defaults = listOf(
                SkillEntity(
                    id = "seed_battery", name = "battery_check",
                    triggerPatterns = JSONArray(listOf(
                        "battery", "battery level", "battery percent",
                        "how much battery", "what's my battery", "whats my battery"
                    )).toString(),
                    responseTemplate = "Battery is {battery}%.", actionType = "query"
                ),
                SkillEntity(
                    id = "seed_time", name = "time_check",
                    triggerPatterns = JSONArray(listOf(
                        "what time", "what's the time", "whats the time",
                        "current time", "tell me the time", "time is it"
                    )).toString(),
                    responseTemplate = "It's {time} on {date}.", actionType = "query"
                ),
                SkillEntity(
                    id = "seed_date", name = "date_check",
                    triggerPatterns = JSONArray(listOf(
                        "what's the date", "whats the date", "what date",
                        "today's date", "current date", "what day"
                    )).toString(),
                    responseTemplate = "Today is {date}.", actionType = "query"
                ),
                SkillEntity(
                    id = "seed_wifi", name = "wifi_status",
                    triggerPatterns = JSONArray(listOf(
                        "wifi status", "wi-fi status", "wifi name",
                        "what's my wifi", "whats my wifi", "connected to wifi", "wifi network"
                    )).toString(),
                    responseTemplate = "{wifi}.", actionType = "query"
                ),
                SkillEntity(
                    id = "seed_memory", name = "memory_status",
                    triggerPatterns = JSONArray(listOf(
                        "memory usage", "ram usage", "memory status",
                        "how much memory", "available memory", "memory free"
                    )).toString(),
                    responseTemplate = "Available memory: {memory}.", actionType = "query"
                )
            )
            memoryStore.insertSkills(defaults)
            Log.i(TAG, "Seeded ${defaults.size} default skills")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed skills: ${e.message}")
        }
    }
}
