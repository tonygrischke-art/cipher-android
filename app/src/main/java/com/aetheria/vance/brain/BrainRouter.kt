package com.aetheria.vance.brain

import android.util.Log
import com.aetheria.vance.actions.ActionExecutor
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Routes user transcripts to the appropriate brain (on-device or cloud),
 * classifies intent, formats prompts, parses responses, and returns structured results.
 *
 * Routing:
 *   DEVICE_ACTION → LiteRT ACTION_MODEL (mobile_actions) → Groq llama-3.3-70b fallback
 *   CONVERSATION  → LiteRT CHAT_MODEL (qwen05) → REASONING_MODEL (gemma-3n) → Groq fallback
 *   REASONING     → LiteRT REASONING_MODEL (gemma-3n) → Groq llama-3.3-70b fallback
 *
 * Returns [BrainResult] with optional action JSON and spoken response text.
 */
class BrainRouter(
    private val liteRTEngine: LiteRTEngine,
    private val groqClient: GroqClient,
    private val actionExecutor: ActionExecutor
) {
    companion object {
        private const val TAG = "BrainRouter"
        private const val GROQ_MODEL = "llama-3.3-70b-versatile"
        private const val GROQ_TIMEOUT_MS = 15_000L

        val DEVICE_ACTION_KEYWORDS = listOf(
            "turn on", "turn off", "toggle",
            "open", "close", "launch", "start", "kill",
            "call", "text", "message", "dial", "send", "reply",
            "set", "change", "adjust",
            "volume", "brightness", "wifi", "wi-fi", "bluetooth",
            "airplane", "flashlight", "torch",
            "alarm", "timer", "screenshot",
            "enable", "disable", "switch"
        )

        val REASONING_KEYWORDS = listOf(
            "summarize", "explain", "what is", "what's", "how do", "how does",
            "analyze", "compare", "difference", "debug", "fix", "check",
            "read", "tell me", "what's happening", "why", "calculate",
            "review", "describe", "define", "translate"
        )

        const val CIPHER_SYSTEM_PROMPT = "You are Cipher, an autonomous AI agent running on an Android device. " +
            "You have access to shell commands via Shizuku, can interact with any app via Accessibility Service, " +
            "and can read/send messages. Be concise. When executing actions respond with JSON action blocks."
    }

    enum class Intent { DEVICE_ACTION, REASONING, CONVERSATION }

    data class BrainResult(
        val actionJson: String? = null,
        val spokenResponse: String
    )

    suspend fun route(transcript: String, context: String, history: String = ""): BrainResult {
        Log.d(TAG, "Routing: \"$transcript\"")
        val intent = classifyIntent(transcript)
        Log.d(TAG, "Intent classified as: $intent")

        return when (intent) {
            Intent.DEVICE_ACTION -> handleDeviceAction(transcript, context)
            Intent.REASONING -> handleReasoning(transcript, context, history)
            Intent.CONVERSATION -> handleConversation(transcript, context, history)
        }
    }

    fun classifyIntent(transcript: String): Intent {
        val lower = transcript.lowercase().trim()
        if (DEVICE_ACTION_KEYWORDS.any { lower.contains(it) }) return Intent.DEVICE_ACTION
        if (REASONING_KEYWORDS.any { lower.contains(it) }) return Intent.REASONING
        return Intent.CONVERSATION
    }

    private suspend fun handleDeviceAction(transcript: String, context: String): BrainResult {
        val prompt = buildActionPrompt(transcript, context)

        // Tier 1: On-device action model
        if (liteRTEngine.isModelAvailable(LiteRTEngine.ModelSlot.ACTION)) {
            try {
                val raw = withTimeoutOrNull(10_000L) {
                    liteRTEngine.generate(prompt, LiteRTEngine.ModelSlot.ACTION)
                }
                if (raw != null) {
                    val text = raw.text
                    val json = extractActionJson(text)
                    val spoken = extractSpokenResponse(text)
                    return BrainResult(actionJson = json, spokenResponse = spoken)
                }
                Log.w(TAG, "Action model timed out, falling back to Groq")
            } catch (e: LiteRTEngine.ModelNotFoundException) {
                Log.w(TAG, "Action model not found: ${e.slot}")
            } catch (e: Exception) {
                Log.w(TAG, "Action model failed: ${e.message}")
            }
        }

        // Tier 2: Groq fallback
        val groqResponse = try {
            withTimeoutOrNull(GROQ_TIMEOUT_MS) {
                groqClient.complete(prompt, GROQ_MODEL, CIPHER_SYSTEM_PROMPT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq fallback failed", e)
            null
        }

        if (groqResponse != null) {
            val json = extractActionJson(groqResponse)
            val spoken = extractSpokenResponse(groqResponse)
            return BrainResult(actionJson = json, spokenResponse = spoken)
        }

        return BrainResult(spokenResponse = "Sorry, I couldn't process that right now.")
    }

    private suspend fun handleReasoning(transcript: String, context: String, history: String): BrainResult {
        val prompt = buildReasoningPrompt(transcript, context, history)

        // Tier 1 : On-device reasoning model
        if (liteRTEngine.isModelAvailable(LiteRTEngine.ModelSlot.REASONING)) {
            try {
                val raw = withTimeoutOrNull(45_000L) {
                    liteRTEngine.generate(prompt, LiteRTEngine.ModelSlot.REASONING)
                }
                if (raw != null) {
                    return BrainResult(spokenResponse = raw.text.trim())
                }
                Log.w(TAG, "Reasoning model timed out, falling back to Groq")
            } catch (e: LiteRTEngine.ModelNotFoundException) {
                Log.w(TAG, "Reasoning model not found: ${e.slot}")
            } catch (e: Exception) {
                Log.w(TAG, "Reasoning model failed: ${e.message}")
            }
        }

        // Tier 2: Groq fallback
        val groqResponse = try {
            withTimeoutOrNull(GROQ_TIMEOUT_MS) {
                groqClient.complete(prompt, GROQ_MODEL, CIPHER_SYSTEM_PROMPT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq fallback failed", e)
            null
        }

        return BrainResult(
            spokenResponse = groqResponse?.trim() ?: "Sorry, I'm having trouble thinking right now."
        )
    }

    private suspend fun handleConversation(transcript: String, context: String, history: String): BrainResult {
        val prompt = buildConversationPrompt(transcript, context, history)

        // Tier 1: On-device CHAT model (qwen05.task via MediaPipe)
        if (liteRTEngine.isModelAvailable(LiteRTEngine.ModelSlot.CHAT)) {
            try {
                val raw = withTimeoutOrNull(45_000L) {
                    liteRTEngine.generate(prompt, LiteRTEngine.ModelSlot.CHAT)
                }
                if (raw != null) {
                    return BrainResult(spokenResponse = raw.text.trim())
                }
                Log.w(TAG, "CHAT model timed out, trying REASONING model")
            } catch (e: Exception) {
                Log.w(TAG, "CHAT model failed: ${e.message}")
            }
        }

        // Tier 2: On-device REASONING model (gemma-3n fallback)
        if (liteRTEngine.isModelAvailable(LiteRTEngine.ModelSlot.REASONING)) {
            try {
                val raw = withTimeoutOrNull(45_000L) {
                    liteRTEngine.generate(prompt, LiteRTEngine.ModelSlot.REASONING)
                }
                if (raw != null) {
                    return BrainResult(spokenResponse = raw.text.trim())
                }
                Log.w(TAG, "REASONING model timed out, falling back to Groq")
            } catch (e: Exception) {
                Log.w(TAG, "REASONING model failed: ${e.message}")
            }
        }

        // Tier 3: Groq fallback
        val groqResponse = try {
            withTimeoutOrNull(GROQ_TIMEOUT_MS) {
                groqClient.complete(prompt, GROQ_MODEL, CIPHER_SYSTEM_PROMPT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq fallback failed", e)
            null
        }

        return BrainResult(
            spokenResponse = groqResponse?.trim() ?: "Tell me more."
        )
    }

    // ── Prompt formatters ──────────────────────────────────────────

    private fun buildActionPrompt(transcript: String, context: String): String =
        "You are a phone control AI. User says: $transcript. " +
            "Context: $context. " +
            "Respond with JSON: {action_type, parameters, spoken_response}"

    private fun buildReasoningPrompt(transcript: String, context: String, history: String): String {
        val historyBlock = if (history.isNotBlank()) "\n\nRecent conversation:\n$history" else ""
        return "You are Cipher, an AI assistant. Context: $context$historyBlock\n\nUser: $transcript"
    }

    private fun buildConversationPrompt(transcript: String, context: String, history: String): String {
        val historyBlock = if (history.isNotBlank()) "\n\nRecent conversation:\n$history" else ""
        return "You are Cipher, an AI assistant. Context: $context$historyBlock\n\nUser: $transcript"
    }

    // ── Response parsing ───────────────────────────────────────────

    /**
     * Extract a JSON action block from raw model output.
     * Looks for { ... } containing "action_type".
     */
    private fun extractActionJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        val block = raw.substring(start, end + 1)
        return try {
            val json = JSONObject(block)
            if (json.has("action_type") || json.has("type")) block else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract spoken_response from JSON, or return the full text.
     */
    private fun extractSpokenResponse(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            try {
                val json = JSONObject(raw.substring(start, end + 1))
                val spoken = json.optString("spoken_response", "")
                if (spoken.isNotBlank()) return spoken
            } catch (_: Exception) {}
        }
        return raw.trim()
    }
}
