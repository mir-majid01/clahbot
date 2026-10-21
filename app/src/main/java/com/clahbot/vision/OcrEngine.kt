package com.clahbot.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun readLootRegion(bitmap: Bitmap, screenW: Int, screenH: Int): Triple<String?, String?, String?>? {
        val goldRegion = crop(bitmap, (screenW * 0.1).toInt(), (screenH * 0.08).toInt(), (screenW * 0.35).toInt(), (screenH * 0.14).toInt())
        val elixirRegion = crop(bitmap, (screenW * 0.35).toInt(), (screenH * 0.08).toInt(), (screenW * 0.6).toInt(), (screenH * 0.14).toInt())
        val darkRegion = crop(bitmap, (screenW * 0.6).toInt(), (screenH * 0.08).toInt(), (screenW * 0.85).toInt(), (screenH * 0.14).toInt())

        val gold = recognizeText(goldRegion)
        val elixir = recognizeText(elixirRegion)
        val dark = recognizeText(darkRegion)

        goldRegion.recycle()
        elixirRegion.recycle()
        darkRegion.recycle()

        return Triple(gold, elixir, dark)
    }

    suspend fun readFullScreen(bitmap: Bitmap): String? {
        return recognizeText(bitmap)
    }

    private suspend fun recognizeText(bitmap: Bitmap): String? {
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text.ifBlank { null })
                }
                .addOnFailureListener {
                    cont.resume(null)
                }
        }
    }

    fun parseLootValue(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        val cleaned = text.replace(Regex("[^0-9KkMm]"), "")
        if (cleaned.isEmpty()) return 0

        val multiplier = when {
            cleaned.endsWith("M", true) -> 1_000_000L
            cleaned.endsWith("K", true) -> 1_000L
            else -> 1L
        }
        val numPart = cleaned.replace(Regex("[KkMm]"), "").toLongOrNull() ?: return 0
        return numPart * multiplier
    }

    private fun crop(bitmap: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Bitmap {
        val left = x1.coerceIn(0, bitmap.width - 1)
        val top = y1.coerceIn(0, bitmap.height - 1)
        val right = x2.coerceIn(left + 1, bitmap.width)
        val bottom = y2.coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }
}
