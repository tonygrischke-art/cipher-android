package com.aetheria.vance.brain

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLite inference engine with NNAPI NPU delegation for MediaTek MT6878.
 *
 * Uses NnApiDelegate which routes through Android NNAPI → NeuroPilot on MT6878.
 * Falls back to CPU if NNAPI init fails.
 *
 * NOTE: TFLite 2.15.0 does NOT support setAcceleratorName() — that was added
 * in 2.17+. NNAPI auto-selects the accelerator based on execution preference
 * and model compatibility. SUSTAINED_SPEED hints toward NPU.
 */
class TfliteEngine(private val context: Context) {

    companion object {
        private const val TAG = "TfliteEngine"
    }

    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null

    val isReady: Boolean get() = interpreter != null

    /**
     * Initialize TFLite interpreter with NNAPI NPU delegation.
     * Falls back to CPU if NNAPI fails.
     */
    fun init(modelFile: File): Boolean {
        return try {
            val nnApiOptions = NnApiDelegate.Options()
                .setExecutionPreference(
                    NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
                )
            // NOTE: setAcceleratorName() not available in TFLite 2.15.0
            // NNAPI will auto-select accelerator on MT6878 (should pick NeuroPilot NPU)

            nnApiDelegate = NnApiDelegate(nnApiOptions)

            val interpreterOptions = Interpreter.Options()
                .addDelegate(nnApiDelegate!!)
                .setNumThreads(4)

            // Load model as mapped ByteBuffer for efficient memory usage
            val modelBuffer = modelFile.inputStream().use { stream ->
                val bytes = stream.readBytes()
                ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    rewind()
                }
            }

            interpreter = Interpreter(modelBuffer, interpreterOptions)

            val inputShape = interpreter!!.getInputTensor(0).shape()
            val outputShape = interpreter!!.getOutputTensor(0).shape()
            Log.i(TAG, "NNPU init OK — model=${modelFile.name} input=${inputShape.contentToString()} output=${outputShape.contentToString()}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI delegate failed: ${e.message} — falling back to CPU")
            tryInitCpu(modelFile)
        }
    }

    /**
     * CPU fallback initialization.
     */
    private fun tryInitCpu(modelFile: File): Boolean {
        return try {
            val options = Interpreter.Options().setNumThreads(4)
            val modelBuffer = modelFile.inputStream().use { stream ->
                val bytes = stream.readBytes()
                ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    rewind()
                }
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "CPU fallback init OK — model=${modelFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "CPU fallback also failed: ${e.message}")
            false
        }
    }

    /**
     * Run inference. Caller provides pre-processed input buffer matching model input shape.
     */
    fun runInference(inputBuffer: ByteBuffer): ByteBuffer? {
        val interp = interpreter ?: return null
        return try {
            val outputShape = interp.getOutputTensor(0).shape()
            val outputSize = outputShape.reduce { a, b -> a * b }
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
                .order(ByteOrder.nativeOrder())
            interp.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()
            outputBuffer
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            null
        }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        try { nnApiDelegate?.close() } catch (_: Exception) {}
        interpreter = null
        nnApiDelegate = null
    }
}
