package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLite LLM inference engine with NNAPI NPU delegation for MediaTek MT6878.
 *
 * Loads a prefill/decode TFLite model (e.g. qwen05.tflite) and drives it
 * with a simple autoregressive generation loop using a SentencePiece tokenizer.
 *
 * Model I/O (from qwen05.tflite):
 *   Input 0: "tokens"    — int32[1, seq_len] — input token IDs
 *   Input 1: "input_pos" — int32[1, seq_len] — position IDs
 *   Output 0: "logits"   — float[1, seq_len, vocab_size] — next-token logits
 *   Output 1: "kv_cache_v_*" — updated KV cache tensors
 *
 * The engine:
 * 1. Tokenizes prompt → int[] token IDs
 * 2. Runs prefill pass (all input tokens at once) → gets logits + KV cache
 * 3. Samples next token from logits (greedy argmax for speed)
 * 4. Feeds back single token + new position → decode pass
 * 5. Repeats until EOS or max tokens
 * 6. Detokenizes output → text
 */
class TfliteLlmEngine(private val context: Context) {

    companion object {
        private const val TAG = "TfliteLlmEngine"

        // Special tokens from Qwen tokenizer (from bundle metadata)
        private const val BOS_TOKEN = 151643     // <|endoftext|> (also used as BOS)
        private const val EOS_TOKEN = 151645     // <|im_end|>
        private const val IM_START_TOKEN = 151644 // <|im_start|>
        private const val IM_END_TOKEN = 151645   // <|im_end|>

        private const val MAX_NEW_TOKENS = 512
        private const val SYS_PROMPT = "You are Qwen, created by Alibaba Cloud. You are a helpful assistant."
    }

    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null

    // Token IDs for the current generation session
    private var promptTokenCount = 0

    val isReady: Boolean get() = interpreter != null

    /**
     * Initialize the TFLite LLM engine.
     * @param modelFile Path to the .tflite model file (e.g. qwen05.tflite)
     */
    fun init(modelFile: File): Boolean {
        Log.i(TAG, "init: loading ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
        return try {
            // Try NNAPI NPU delegate first
            try {
                val nnApiOptions = NnApiDelegate.Options()
                    .setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                nnApiDelegate = NnApiDelegate(nnApiOptions)

                val options = Interpreter.Options()
                    .addDelegate(nnApiDelegate!!)
                    .setNumThreads(4)
                    .setUseNNAPI(true)

                interpreter = Interpreter(modelFile, options)
                Log.i(TAG, "NNPU delegate initialized successfully")
                printTensorInfo()
                true
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI delegate failed: ${e.message} — falling back to CPU")
                tryInitCpu(modelFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}")
            false
        }
    }

    private fun tryInitCpu(modelFile: File): Boolean {
        return try {
            val options = Interpreter.Options().setNumThreads(4)
            interpreter = Interpreter(modelFile, options)
            Log.i(TAG, "CPU fallback initialized successfully")
            printTensorInfo()
            true
        } catch (e: Exception) {
            Log.e(TAG, "CPU fallback also failed: ${e.message}")
            false
        }
    }

    private fun printTensorInfo() {
        val interp = interpreter ?: return
        Log.i(TAG, "=== Model Tensor Info ===")
        Log.i(TAG, "Input tensors:")
        for (i in 0 until interp.inputTensorCount) {
            val t = interp.getInputTensor(i)
            Log.i(TAG, "  [$i] name=${t.name()} shape=${t.shape().contentToString()} dtype=${t.dataType()}")
        }
        Log.i(TAG, "Output tensors:")
        for (i in 0 until interp.outputTensorCount) {
            val t = interp.getOutputTensor(i)
            Log.i(TAG, "  [$i] name=${t.name()} shape=${t.shape().contentToString()} dtype=${t.dataType()}")
        }
        Log.i(TAG, "========================")
    }

    /**
     * Generate text response for the given prompt.
     * Uses Qwen chat template: <|im_start|>system\n{prompt}<|im_end|>\n<|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n
     */
    fun generate(prompt: String): String {
        val interp = interpreter ?: run {
            Log.e(TAG, "Engine not initialized")
            return "[TfliteLlmEngine not ready]"
        }

        return try {
            // Tokenize the prompt with chat template
            val promptTokens = tokenizeWithTemplate(prompt)
            Log.d(TAG, "Prompt tokens: ${promptTokens.size} tokens")

            // Prefill: feed all prompt tokens at once
            val prefillResult = prefill(interp, promptTokens)
            if (prefillResult == null) {
                Log.e(TAG, "Prefill failed")
                return "[prefill failed]"
            }

            val (logits, kvCache) = prefillResult
            Log.d(TAG, "Prefill logits size: ${logits.size}, kvCache entries: ${kvCache.size}")

            // Decode: generate tokens one at a time
            val outputTokens = decodeLoop(interp, logits, kvCache.toMutableMap())
            Log.d(TAG, "Generated ${outputTokens.size} tokens")

            // Detokenized output
            detokenize(outputTokens)
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed: ${e.message}", e)
            "[generation error: ${e.message}]"
        }
    }

    /**
     * Tokenize the prompt using Qwen chat template.
     * Simplified: wraps prompt in <|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n
     */
    private fun tokenizeWithTemplate(prompt: String): IntArray {
        // Build the full prompt with chat template
        // <|im_start|>system\nYou are Qwen...<|im_end|>\n<|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n
        // For now, use a simpler approach: just the user prompt tokenized
        // TODO: full chat template with system prompt
        return tokenizeSimple(prompt)
    }

    /**
     * Simple character-level + byte-level tokenization fallback.
     * This is a basic approach — for production, use SentencePieceTokenizer.
     *
     * For Qwen, each character is roughly mapped to token IDs.
     * This won't be perfect but demonstrates the inference loop works.
     */
    private fun tokenizeSimple(text: String): IntArray {
        val tokens = mutableListOf<Int>()
        // Add BOS token — Qwen uses 151643 for <|endoftext|> as BOS
        // Actually for the prefill model, we might not need BOS
        // The model should handle the start of sequence

        // Simple approach: use character codes as token IDs
        // This is WRONG for real inference but the loop will work
        // We need the actual tokenizer for correct results

        // For now, use a basic byte-pair approach
        // Each byte value maps to token ID 128 + byte_val (hack for testing)
        // Real SentencePiece would give proper token IDs
        for (char in text) {
            val code = char.code
            if (code < 128) {
                tokens.add(code)
            } else {
                // Multi-byte UTF-8: decompose into bytes
                val bytes = char.toString().toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    tokens.add((b.toInt() and 0xFF) + 128)
                }
            }
        }
        return tokens.toIntArray()
    }

    /**
     * Prefill pass: feed all prompt tokens at once to fill the KV cache.
     * Returns Pair of (logits FloatArray, KV cache outputs map).
     */
    private fun prefill(interp: Interpreter, tokens: IntArray): Pair<FloatArray, Map<Int, ByteBuffer>>? {
        return try {
            val seqLen = tokens.size

            // Input 0: tokens [1, seq_len]
            val inputTokensBuf = ByteBuffer.allocateDirect(seqLen * 4).apply {
                order(ByteOrder.nativeOrder())
                for (t in tokens) putInt(t)
                rewind()
            }

            // Input 1: position IDs [1, seq_len]
            val inputPosBuf = ByteBuffer.allocateDirect(seqLen * 4).apply {
                order(ByteOrder.nativeOrder())
                for (i in tokens.indices) putInt(i)
                rewind()
            }

            // Output buffers
            val outputTensor0 = interp.getOutputTensor(0)
            val outputSize0 = outputTensor0.shape().reduce { a, b -> a * b }
            val outputBuf0 = ByteBuffer.allocateDirect(outputSize0 * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            val inputs = arrayOf(inputTokensBuf, inputPosBuf)
            val outputs = mutableMapOf<Int, Any>()
            outputs[0] = outputBuf0

            // KV cache output buffers (output tensors 1+)
            val kvCacheOutputs = mutableMapOf<Int, ByteBuffer>()
            for (i in 1 until interp.outputTensorCount) {
                val t = interp.getOutputTensor(i)
                val bufSize = t.shape().reduce { a, b -> a * b }
                val buf = ByteBuffer.allocateDirect(bufSize * 4).apply {
                    order(ByteOrder.nativeOrder())
                }
                outputs[i] = buf
                kvCacheOutputs[i] = buf
            }

            interp.runForMultipleInputsOutputs(inputs, outputs)
            Log.d(TAG, "Prefill complete: seqLen=$seqLen, outputs=${interp.outputTensorCount}")
            promptTokenCount = seqLen

            val logits = extractLastTokenLogits(outputBuf0, outputTensor0.shape(), seqLen)
            Pair(logits, kvCacheOutputs)
        } catch (e: Exception) {
            Log.e(TAG, "Prefill failed: ${e.message}", e)
            null
        }
    }

    /**
     * Decode loop: generate tokens one at a time using the KV cache.
     */
    private fun decodeLoop(interp: Interpreter, prefillLogits: FloatArray, kvCache: MutableMap<Int, ByteBuffer>): IntArray {
        val outputTokens = mutableListOf<Int>()
        val maxNewTokens = MAX_NEW_TOKENS

        var nextToken = greedyArgmax(prefillLogits)
        outputTokens.add(nextToken)
        Log.d(TAG, "First generated token: $nextToken")

        for (step in 1 until maxNewTokens) {
            if (nextToken == EOS_TOKEN || nextToken == IM_END_TOKEN) {
                Log.d(TAG, "EOS reached at step $step")
                break
            }

            val nextPos = promptTokenCount + outputTokens.size - 1
            val singleToken = ByteBuffer.allocateDirect(4).apply {
                order(ByteOrder.nativeOrder())
                putInt(nextToken)
                rewind()
            }
            val singlePos = ByteBuffer.allocateDirect(4).apply {
                order(ByteOrder.nativeOrder())
                putInt(nextPos)
                rewind()
            }

            // Build inputs: [token, pos, kv_cache_0, kv_cache_1, ...]
            // KV cache input tensor indices: 2, 3, 4, ... (after tokens and pos)
            // KV cache output tensor indices: 1, 2, 3, ... (after logits at 0)
            val inputArray = arrayOfNulls<Any>(interp.inputTensorCount)
            inputArray[0] = singleToken
            inputArray[1] = singlePos
            for (kvIdx in kvCache.keys) {
                // Map: output tensor kvIdx -> input tensor (kvIdx + 1)
                val inIdx = kvIdx + 1
                if (inIdx < inputArray.size) {
                    inputArray[inIdx] = kvCache[kvIdx]
                }
            }

            // Build outputs: [logits, kv_cache_0_new, kv_cache_1_new, ...]
            val outputs = mutableMapOf<Int, Any>()
            val outputTensor0 = interp.getOutputTensor(0)
            val outputSize0 = outputTensor0.shape().reduce { a, b -> a * b }
            val outputBuf0 = ByteBuffer.allocateDirect(outputSize0 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            outputs[0] = outputBuf0

            val newKvCache = mutableMapOf<Int, ByteBuffer>()
            for (i in 1 until interp.outputTensorCount) {
                val t = interp.getOutputTensor(i)
                val bufSize = t.shape().reduce { a, b -> a * b }
                val buf = ByteBuffer.allocateDirect(bufSize * 4).apply {
                    order(ByteOrder.nativeOrder())
                }
                outputs[i] = buf
                newKvCache[i] = buf
            }

            try {
                interp.runForMultipleInputsOutputs(inputArray, outputs)
            } catch (e: Exception) {
                Log.e(TAG, "Decode step $step failed: ${e.message}")
                break
            }

            // Update KV cache for next iteration
            kvCache.clear()
            kvCache.putAll(newKvCache)

            val logits = extractLastTokenLogits(outputBuf0, outputTensor0.shape(), 1)
            nextToken = greedyArgmax(logits)
            outputTokens.add(nextToken)
            Log.v(TAG, "Decode step $step: token=$nextToken")
        }

        return outputTokens.toIntArray()
    }

    private fun extractLastTokenLogits(buffer: ByteBuffer, shape: IntArray, seqLen: Int): FloatArray {
        buffer.rewind()
        // shape is [1, seq_len, vocab_size] or [seq_len, vocab_size]
        val vocabSize = shape.last()
        val floats = FloatArray(buffer.remaining() / 4)
        buffer.asFloatBuffer().get(floats)

        // Get the last token's logits
        val lastTokenOffset = (seqLen - 1) * vocabSize
        return if (lastTokenOffset + vocabSize <= floats.size) {
            floats.copyOfRange(lastTokenOffset, lastTokenOffset + vocabSize)
        } else {
            // Fallback: return the last vocab_size floats
            floats.takeLast(vocabSize).toFloatArray()
        }
    }

    private fun greedyArgmax(logits: FloatArray): Int {
        var maxIdx = 0
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (logits[i] > maxVal) {
                maxVal = logits[i]
                maxIdx = i
            }
        }
        return maxIdx
    }

    /**
     * Detokenize token IDs back to text.
     * Simple: treat each token ID as a byte/char code.
     */
    private fun detokenize(tokens: IntArray): String {
        val sb = StringBuilder()
        for (token in tokens) {
            when {
                token in 0..127 -> {
                    // ASCII
                    sb.append(token.toChar())
                }
                token in 128..383 -> {
                    // Multi-byte: recombine
                    sb.append((token - 128).toInt().toChar())
                }
                token == EOS_TOKEN || token == IM_END_TOKEN -> {
                    // Stop at EOS
                    break
                }
                else -> {
                    // Unknown token: skip or replace
                    // In a real implementation, we'd have the vocab table
                }
            }
        }
        return sb.toString()
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        try { nnApiDelegate?.close() } catch (_: Exception) {}
        interpreter = null
        nnApiDelegate = null
    }
}
