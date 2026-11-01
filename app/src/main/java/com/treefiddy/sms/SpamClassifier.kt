package com.treefiddy.sms

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * On-device political-spam classifier: WordPiece tokenize -> MobileBERT LiteRT
 * (int8) -> softmax. Model I/O (verified from the exported .tflite):
 *   input 0 (args_0) = input_ids       int64 [1,128]
 *   input 1 (args_1) = attention_mask  int64 [1,128]
 *   output   (output_0)                float32 [1,2]  (logits: [ham, spam])
 * The .tflite MUST be exported with --seq 128 (export_litert.py) to match
 * maxLen below — a 64-length export fails tensor allocation at runtime.
 *
 * Load once and reuse (thread-confined, or guard classify() with a lock).
 */
class SpamClassifier(
    context: Context,
    modelAsset: String = "mobilebert_spam_int8.tflite",
    vocabAsset: String = "vocab.txt",
    // Must match the exported .tflite's fixed input length. Trained/distilled at 128;
    // 64 was too short — long advocacy texts (~87+ tokens) had their "ask" truncated.
    private val maxLen: Int = 128,
    numThreads: Int = 4,
    useXnnpack: Boolean = true,   // correct with LiteRT; ~20 ms/msg at seq 128 on Pixel 10 Pro XL
) {
    data class Result(val isSpam: Boolean, val pSpam: Float)

    private val interpreter: Interpreter
    private val tokenizer: WordpieceTokenizer

    init {
        val opts = Interpreter.Options().apply {
            setNumThreads(numThreads)
            setUseXNNPACK(useXnnpack)
        }
        interpreter = Interpreter(loadModel(context, modelAsset), opts)
        val vocabLines = context.assets.open(vocabAsset).bufferedReader().readLines()
        tokenizer = WordpieceTokenizer.fromVocabLines(vocabLines, maxLen)
    }

    /** @param threshold p(spam) at/above which we call it spam. Raise to favor precision.
     *  Long texts are scored as head + tail windows, max-pooled: the donate ask
     *  and opt-out footer often sit past token maxLen behind a narrative opener. */
    fun classify(text: String, threshold: Float = 0.5f): Result {
        val pSpam = tokenizer.encodeWindows(text).maxOf { runWindow(it) }
        return Result(pSpam >= threshold, pSpam)
    }

    private fun runWindow(enc: WordpieceTokenizer.Encoded): Float {
        val inputIds = arrayOf(enc.inputIds)          // [1,128] int64
        val attnMask = arrayOf(enc.attentionMask)     // [1,128] int64
        val output = arrayOf(FloatArray(2))           // [1,2] float32

        // input order matches tensor indices: 0=input_ids, 1=attention_mask
        interpreter.runForMultipleInputsOutputs(
            arrayOf(inputIds, attnMask), mapOf(0 to output)
        )

        val logits = output[0]
        val m = maxOf(logits[0], logits[1])
        val e0 = exp((logits[0] - m).toDouble())
        val e1 = exp((logits[1] - m).toDouble())
        return (e1 / (e0 + e1)).toFloat()
    }

    fun close() = interpreter.close()

    private fun loadModel(context: Context, asset: String): MappedByteBuffer {
        context.assets.openFd(asset).use { afd ->
            afd.createInputStream().channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
}
