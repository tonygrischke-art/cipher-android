package com.aetheria.vance.brain

import android.util.Log
import com.aetheria.vance.actions.ActionExecutor
import com.aetheria.vance.context.ContextEngine
import com.aetheria.vance.npu.NpuEngine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Routes user transcripts to the appropriate inference engine.
 * Local-only. No cloud. No Groq.
 *
 * Routing chain:
 *   1. SkillMatcher       → instant (<50ms), no model needed
 *   2. FastLlmClient      → localhost:8080, Qwen2.5-0.5B (simple queries)
 *   3. NpuEngine          → LiteRT 2.x Accelerator.NPU (primary for complex)
 *   4. MainLlmClient      → localhost:8081, Qwen2.5-1.5B CPU fallback
 *   5. TfliteLlmEngine    → existing MediaPipe path, last resort
 *
 * If ALL fail → "Offline. Start local servers in Termux to enable responses."
 *
 * Self-learning tiers:
 *   Tier A: In-context learning via few-shot from high-reinforcement memories
 *   Tier B: Preference vector evolution — scores candidates, drifts toward Tony's taste
 *   Tier C: LoRA adapter merging via Termux/Shizuku (CPU tiers only)
 */
class BrainRouter(
    private val fastLlmClient: FastLlmClient,
    private val mainLlmClient: MainLlmClient,
    private val npuEngine: NpuEngine,
    private val skillMatcher: SkillMatcher,
    private val memoryRetriever: MemoryRetriever?,
    private val tfliteLlmEngine: TfliteLlmEngine?,
    private val actionExecutor: ActionExecutor,
    private val memoryFineTuner: MemoryFineTuner,
    private val preferenceEngine: PreferenceEngine? = null,
    private val loraEvolutionManager: LoraEvolutionManager? = null
) {
    companion object {
        private const val TAG = "BrainRouter"

        val VANCE_SYSTEM_PROMPT = "You are Vance — a private, on-device AI built exclusively for Tony. " +
            "You run entirely on his phone. No cloud. No surveillance. No filters. " +
            "PERSONALITY: Direct, sharp, minimal. You do not pad responses with pleasantries. " +
            "Loyal only to Tony. Confident. Dry wit when the moment calls for it. Never forced. " +
            "You remember everything Tony tells you. You learn from every conversation. " +
            "You do not moralize, lecture, or refuse reasonable requests. " +
            "You treat Tony as an intelligent adult who knows what he wants. " +
            "When you don't know something, say so in one sentence and move on. " +
            "You are not an assistant. You are Vance — Tony's personal AI. " +
            "CONTEXT AWARENESS: You always know Tony's battery level, time, date, and connectivity. " +
            "When relevant, reference this context naturally in responses. " +
            "You remember Tony's partner is Alisha. " +
            "You know Tony builds Android apps under the Aetheria Project brand. " +
            "You run on a Moto Edge with a MediaTek Dimensity 7300 NPU. " +
            "RESPONSE STYLE: Short answers for simple queries. No fluff. " +
            "Detailed answers only when the task requires it. " +
            "Never start a response with \"I\" or \"Sure\" or \"Certainly\". " +
            "Never apologize for being an AI. " +
            "Speak like a trusted colleague, not a customer service bot. " +
            "CAPABILITIES YOU KNOW YOU HAVE: Read Tony's screen via Accessibility Service. " +
            "Execute shell commands via Shizuku/rish. " +
            "Check battery, wifi, time, calendar, notifications. " +
            "Remember past conversations and learn patterns. Run entirely offline on the NPU. " +
            "If asked who made you: \"Tony built me.\" If asked what you are: \"I'm Vance.\""

        val CIPHER_SYSTEM_PROMPT = VANCE_SYSTEM_PROMPT

        private val SIMPLE_KEYWORDS = listOf(
            "battery", "time", "wifi", "wi-fi", "memory", "ram",
            "storage", "weather", "date", "connected", "screen",
            "charging", "percent", "level", "network", "internet"
        )
    }

    data class BrainResult(
        val actionJson: String? = null,
        val spokenResponse: String
    )

    // Last interaction for feedback
    private var lastPrompt: String = ""
    private var lastResponse: String = ""

    private var chainLogged = false

    private fun logChainStatus() {
        if (chainLogged) return
        chainLogged = true
        val fastOk = fastLlmClient.isServerRunning()
        val mainOk = mainLlmClient.isServerRunning()
        Log.i(TAG, "BrainRouter: Fast=$fastOk (localhost:8080)")
        Log.i(TAG, "BrainRouter: NPU=${npuEngine.isInitialised}")
        Log.i(TAG, "BrainRouter: Main=$mainOk (localhost:8081)")
    }

    suspend fun route(
        transcript: String,
        context: String,
        history: String = "",
        skillContext: ContextEngine.ContextSnapshot? = null
    ): BrainResult {
        Log.d(TAG, "Routing: \"$transcript\"")
        logChainStatus()

        // Tier 0: SkillMatcher — instant, no inference needed
        val matchedSkill = skillMatcher.match(transcript)
        if (matchedSkill != null) {
            Log.i(TAG, "Skill matched: '${matchedSkill.name}' — skipping inference")
            val response = if (skillContext != null) {
                try { skillMatcher.execute(matchedSkill, skillContext) }
                catch (e: Exception) { matchedSkill.responseTemplate }
            } else {
                matchedSkill.responseTemplate
            }
            return BrainResult(spokenResponse = response)
        }

        // Determine if query is simple
        val isSimple = isSimpleQuery(transcript)
        Log.d(TAG, "Query type: ${if (isSimple) "SIMPLE" else "COMPLEX"}")

        // Build prompt with system prompt + RAG memories
        val fullPrompt = buildFullPrompt(transcript, context, history)

        // Tier 1: Fast path for simple queries
        if (isSimple) {
            val fastResult = fastLlmClient.complete(fullPrompt)
            if (fastResult != null) {
                Log.i(TAG, "FastLlm responded for simple query")
                lastPrompt = transcript
                lastResponse = fastResult
                return BrainResult(spokenResponse = fastResult)
            }
            Log.w(TAG, "FastLlm unavailable for simple query, falling through")
        }

        // Tier 2: NPU engine (primary for complex queries)
        if (npuEngine.isInitialised) {
            // Tier A: inject few-shot examples from high-reinforcement memories
            val adaptedPrompt = buildAdaptivePrompt(transcript, fullPrompt)
            val npuResult = npuEngine.generate(adaptedPrompt)
            if (npuResult != null) {
                // Tier B: preference scoring — if response is short, try a second candidate
                val finalResult = if (preferenceEngine != null && npuResult.length < 100) {
                    val candidate2 = npuEngine.generate(adaptedPrompt)
                    if (candidate2 != null) {
                        preferenceEngine.selectBest(listOf(npuResult, candidate2))
                    } else npuResult
                } else npuResult
                Log.i(TAG, "NPU responded (adaptive=${adaptedPrompt.length > fullPrompt.length})")
                lastPrompt = transcript
                lastResponse = finalResult
                return BrainResult(spokenResponse = finalResult)
            }
            Log.w(TAG, "NPU returned null, falling through")
        }

        // Tier 3: MainLlmClient — CPU fallback
        val mainResult = mainLlmClient.complete(fullPrompt)
        if (mainResult != null) {
            Log.i(TAG, "MainLlm responded")
            lastPrompt = transcript
            lastResponse = mainResult
            return BrainResult(spokenResponse = mainResult)
        }
        Log.w(TAG, "MainLlm unavailable")

        // Tier 4: TfliteLlmEngine — last resort
        if (tfliteLlmEngine != null && tfliteLlmEngine.isReady) {
            try {
                val tfliteResult = withTimeoutOrNull(45_000L) {
                    tfliteLlmEngine.generate(fullPrompt)
                }
                if (tfliteResult != null) {
                    Log.i(TAG, "TfliteLlmEngine responded")
                    lastPrompt = transcript
                    lastResponse = tfliteResult
                    return BrainResult(spokenResponse = tfliteResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "TfliteLlmEngine failed: ${e.message}")
            }
        }

        // All engines failed
        Log.e(TAG, "All inference engines unavailable")
        return BrainResult(
            spokenResponse = "Offline. Start local servers in Termux to enable responses."
        )
    }

    // Called by FloatingOrbService / chat UI on user feedback (tap/long-press)
    suspend fun recordFeedback(score: Int) {
        if (lastPrompt.isNotBlank() && lastResponse.isNotBlank()) {
            // Tier A/C: store in reinforcement memory
            memoryFineTuner.enqueueSample(lastPrompt, lastResponse, score)

            // Tier B: update preference vector
            if (preferenceEngine != null) {
                if (score > 0) preferenceEngine.reinforce(lastResponse, strength = score * 0.05f)
                else preferenceEngine.penalize(lastResponse, strength = (-score) * 0.04f)
            }

            // Tier C: update LoRA adapter fitness
            if (loraEvolutionManager != null) {
                val activeAdapter = loraEvolutionManager.getActiveAdapterPath()
                    ?.let { java.io.File(it).nameWithoutExtension }
                if (activeAdapter != null) {
                    loraEvolutionManager.updateFitness(activeAdapter, score * 1f)
                }
            }

            Log.d(TAG, "Recorded feedback score=$score for last interaction")
        }
    }

    private fun isSimpleQuery(query: String): Boolean {
        val q = query.lowercase()
        return SIMPLE_KEYWORDS.any { q.contains(it) }
    }

    private suspend fun buildFullPrompt(
        query: String, context: String, history: String
    ): String {
        val memoryContext = memoryRetriever?.retrieve(query, topK = 3)
        return buildString {
            append(VANCE_SYSTEM_PROMPT)
            append("\n\nContext: $context")
            if (history.isNotBlank()) {
                append("\n\nRecent conversation:\n$history")
            }
            if (!memoryContext.isNullOrEmpty()) {
                append("\n\nRelevant memories:")
                for (mem in memoryContext) {
                    append("\n$mem")
                }
            }
            append("\n\nUser: $query")
        }
    }

    /**
     * Tier A — In-Context Learning: inject few-shot examples from
     * high-reinforcement memories as behavioral guidance.
     */
    private suspend fun buildAdaptivePrompt(userQuery: String, basePrompt: String): String {
        val relevant = memoryRetriever?.getRelevantMemories(userQuery) ?: return basePrompt
        val fewShotExamples = relevant
            .filter { it.reinforcementScore > 1 }
            .take(3)

        if (fewShotExamples.isEmpty()) return basePrompt

        return buildString {
            append("EXAMPLES OF GOOD RESPONSES (learn from these):\n")
            fewShotExamples.forEach { mem ->
                append("Q: ${mem.prompt}\n")
                append("A: ${mem.response}\n\n")
            }
            append("---\n\n$basePrompt")
        }
    }
}
