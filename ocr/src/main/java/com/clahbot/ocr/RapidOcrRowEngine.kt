package com.clahbot.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PP-OCRv4 recognition (PaddleOCR) on ONNX Runtime, used only on row crops.
 * Input is resized to 48 px high in BGR, normalised to [-1, 1], and decoded
 * with CTC greedy decoding against the bundled 6623-character dictionary.
 */
class RapidOcrRowEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val keys: List<String>,
    private val inputName: String
) : Closeable {

    data class Read(val text: String, val input: Bitmap)

    suspend fun recognize(bitmap: Bitmap): Read = withContext(Dispatchers.Default) {
        val targetWidth = max(
            16,
            (TARGET_HEIGHT.toFloat() * bitmap.width / bitmap.height).roundToInt()
        ).coerceAtMost(MAX_WIDTH)
        val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, TARGET_HEIGHT, true)
        val pixels = IntArray(targetWidth * TARGET_HEIGHT)
        resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, TARGET_HEIGHT)
        val plane = targetWidth * TARGET_HEIGHT
        val data = FloatArray(plane * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            data[index] = (blue / 255f - 0.5f) / 0.5f
            data[plane + index] = (green / 255f - 0.5f) / 0.5f
            data[plane * 2 + index] = (red / 255f - 0.5f) / 0.5f
        }

        try {
            val shape = longArrayOf(1, 3, TARGET_HEIGHT.toLong(), targetWidth.toLong())
            val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape)
            try {
                val inputs = mapOf(inputName to tensor)
                val result = session.run(inputs)
                try {
                    @Suppress("UNCHECKED_CAST")
                    val output = result.get(0).value as Array<Array<FloatArray>>
                    return@withContext Read(decode(output[0]), resized)
                } finally {
                    result.close()
                }
            } finally {
                tensor.close()
            }
        } catch (throwable: Throwable) {
            resized.recycle()
            throw throwable
        }
    }

    private fun decode(timesteps: Array<FloatArray>): String {
        val builder = StringBuilder()
        var last = 0
        for (timestep in timesteps) {
            var best = 0
            var bestScore = timestep[0]
            for (index in 1 until timestep.size) {
                if (timestep[index] > bestScore) {
                    bestScore = timestep[index]
                    best = index
                }
            }
            if (best != 0 && best != last) {
                val keyIndex = best - 1
                when {
                    keyIndex in keys.indices -> builder.append(keys[keyIndex])
                    keyIndex == keys.size -> builder.append(' ')
                }
            }
            last = best
        }
        return builder.toString().trim()
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val TARGET_HEIGHT = 48
        private const val MAX_WIDTH = 640
        private const val MODEL_ASSET = "rapidocr/ch_PP-OCRv4_rec_infer.onnx"
        private const val KEYS_ASSET = "rapidocr/ppocr_keys_v1.txt"

        fun create(context: Context): RapidOcrRowEngine {
            val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val keys = context.assets.open(KEYS_ASSET).bufferedReader().use { reader ->
                reader.readLines().map { it.removeSuffix("\r") }
            }
            require(keys.isNotEmpty()) { "RapidOCR dictionary is empty" }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(2)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val session = environment.createSession(model, options)
            val inputName = session.inputNames.firstOrNull()
                ?: throw IllegalStateException("RapidOCR model has no inputs")
            return RapidOcrRowEngine(environment, session, keys, inputName)
        }
    }
}
