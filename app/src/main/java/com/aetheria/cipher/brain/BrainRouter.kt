package com.aetheria.cipher.brain

import android.util.Log
import com.aetheria.cipher.actions.ActionExecutor
import kotlinx.coroutines.flow.Flow

class BrainRouter @JvmOverloads constructor(
    private val liteRTEngine: LiteRTEngine = LiteRTEngine(),
    private val groqClient: GroqClient = GroqClient(),
    private val actionExecutor: ActionExecutor = ActionExecutor()
) {

    companion object {
        private const val TAG = "BrainRouter"
    }

    enum class IntentType {
        DEVICE_ACTION,
        REASONING,
        CONVERSATION
    }

    data class ActionRequest(
        val type: String,
        val params: Map<String, Any> = emptyMap()
    )

    suspend fun process(transcript: String, context: String, onResult: (String) -> Unit) {
        Log.d(TAG, "Processing: \"$transcript\"")

        val intentType = classifyIntent(transcript)
        Log.d(TAG, "Intent: $intentType")

        val response = when (intentType) {
            IntentType.DEVICE_ACTION -> handleDeviceAction(transcript, context)
            IntentType.REASONING -> handleReasoning(transcript, context)
            IntentType.CONVERSATION -> handleConversation(transcript, context)
        }

        onResult(response)
    }

    private fun classifyIntent(transcript: String): IntentType {
        val lower = transcript.lowercase()
        val actionKeywords = listOf(
            "open", "close", "launch", "turn on", "turn off", "toggle",
            "call", "text", "message", "dial", "send", "reply",
            "set", "change", "adjust", "volume", "brightness",
            "screenshot", "scroll", "tap", "click"
        )
        if (actionKeywords.any { lower.contains(it) }) return IntentType.DEVICE_ACTION

        val reasoningKeywords = listOf(
            "why", "how", "explain", "what is", "summarize",
            "analyze", "compare", "difference between", "calculate"
        )
        if (reasoningKeywords.any { lower.contains(it) }) return IntentType.REASONING

        return IntentType.CONVERSATION
    }

    private suspend fun handleDeviceAction(transcript: String, context: String): String {
        val prompt = buildDeviceActionPrompt(transcript, context)

        // Tier 1: functiongemma-270m on-device
        val result = liteRTEngine.infer(prompt, modelSlot = "action")
        if (result.isSuccess) {
            val actionJson = result.getOrNull() ?: return "Sorry, couldn't parse that."
            return actionExecutor.execute(actionJson)
        }

        // Tier 3: Groq fallback
        Log.d(TAG, "Device action on-device failed, falling back to Groq")
        return groqClient.complete(prompt, model = "qwen3-32b")
    }

    private suspend fun handleReasoning(transcript: String, context: String): String {
        val prompt = buildReasoningPrompt(transcript, context)

        // Tier 2: Qwen3.5-2B on-device
        val result = liteRTEngine.infer(prompt, modelSlot = "reasoning")
        if (result.isSuccess) return result.getOrNull() ?: "I'm not sure about that."

        // Tier 3: Groq fallback
        Log.d(TAG, "Reasoning on-device failed, falling back to Groq")
        return groqClient.complete(prompt, model = "kimi-k2")
    }

    private suspend fun handleConversation(transcript: String, context: String): String {
        val prompt = buildConversationPrompt(transcript, context)

        // Tier 2: Qwen3.5-2B on-device
        val result = liteRTEngine.infer(prompt, modelSlot = "reasoning")
        if (result.isSuccess) return result.getOrNull() ?: "Tell me more."

        // Tier 3: Groq fallback
        Log.d(TAG, "Conversation on-device failed, falling back to Groq")
        return groqClient.complete(prompt, model = "kimi-k2")
    }

    private fun buildDeviceActionPrompt(transcript: String, context: String): String {
        return """You are a device action classifier. Given the user's request, output a JSON action object.

Context: $context
User: $transcript

Respond with JSON only, no other text.
Format: {"type": "ACTION_TYPE", "params": {"key": "value"}}

Available types:
- SHELL_COMMAND: {"command": "..."}
- OPEN_APP: {"package": "..."}
- SEND_SMS: {"to": "...", "message": "..."}
- MAKE_CALL: {"number": "..."}
- SYSTEM_SETTING: {"setting": "...", "value": "..."}
- ACCESSIBILITY_ACTION: {"action": "tap|type|scroll", "target": "..."}
- WEB_SEARCH: {"query": "..."}"""
    }

    private fun buildReasoningPrompt(transcript: String, context: String): String {
        return """Context: $context
User: $transcript

Respond helpfully and concisely."""
    }

    private fun buildConversationPrompt(transcript: String, context: String): String {
        return """You are Cipher, a helpful AI assistant. Respond naturally and concisely.

Context: $context
User: $transcript

Cipher:"""
    }
}
