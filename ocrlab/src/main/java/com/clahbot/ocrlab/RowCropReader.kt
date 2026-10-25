package com.clahbot.ocrlab

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

data class EngineRowRead(
    val text: String,
    val value: Long?
)

data class RowReading(
    val role: RowRole,
    val index: Int,
    val resource: ResourceKind?,
    val box: OcrBox,
    val mlKit: EngineRowRead,
    val tesseract: EngineRowRead,
    val combined: Long?,
    val source: String
)

/**
 * Crops the numeric part of one row, upscales it 3x, and reads it with both
 * ML Kit and Tesseract. A value is accepted only when it passes the strict
 * numeric format. Disagreement is a conflict (null), never a guess.
 */
class RowCropReader(
    private val mlKit: OcrEngine,
    private val tesseract: TesseractRowEngine?
) {

    suspend fun read(
        bitmap: Bitmap,
        row: RowCandidate,
        index: Int
    ): RowReading {
        val crop = cropNumber(bitmap, row.box)
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, true)
        crop.recycle()

        val mlText = try {
            bestLine(mlKit.recognize(scaled))
        } catch (_: Throwable) {
            ""
        }
        val mlValue = parse(row.role, mlText)

        val tessBitmap = invertToGrayscale(scaled)
        val tessText = if (tesseract != null) {
            withContext(Dispatchers.IO) {
                runCatching { tesseract.recognize(tessBitmap) }.getOrDefault("")
            }
        } else {
            ""
        }
        tessBitmap.recycle()
        scaled.recycle()
        val tessValue = parse(row.role, tessText)

        val (combined, source) = when {
            mlValue != null && tessValue != null && mlValue == tessValue ->
                mlValue to "both"

            mlValue != null && tessValue != null ->
                null to "conflict"

            mlValue != null -> mlValue to "mlkit"
            tessValue != null -> tessValue to "tesseract"
            else -> null to "none"
        }

        return RowReading(
            role = row.role,
            index = index,
            resource = row.resource,
            box = row.box,
            mlKit = EngineRowRead(mlText, mlValue),
            tesseract = EngineRowRead(tessText, tessValue),
            combined = combined,
            source = source
        )
    }

    private fun parse(role: RowRole, text: String): Long? = when (role) {
        RowRole.BONUS -> NumberParser.parseBonus(text)
        else -> NumberParser.parseGrouped(text)
    }

    private fun bestLine(page: OcrPage): String {
        val candidates = page.lines
            .map { it.text.trim() }
            .filter { line -> line.count { it.isDigit() } >= 3 }
        return candidates.maxByOrNull { line -> line.count { it.isDigit() } }
            ?: page.lines.joinToString(" ") { it.text }.trim()
    }

    private fun cropNumber(bitmap: Bitmap, box: OcrBox): Bitmap {
        val padX = max(2, box.height / 5)
        val padY = max(2, box.height / 5)
        val left = (box.left - padX).coerceIn(0, bitmap.width - 1)
        val top = (box.top - padY).coerceIn(0, bitmap.height - 1)
        val right = (box.right + padX).coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom + padY).coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    /** White game text becomes dark text on a light background for Tesseract. */
    private fun invertToGrayscale(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val luminance = 0.299f * Color.red(color) +
                    0.587f * Color.green(color) +
                    0.114f * Color.blue(color)
                val contrasted = ((luminance - 128f) * 1.6f + 128f).coerceIn(0f, 255f)
                val inverted = (255f - contrasted).toInt()
                output.setPixel(x, y, Color.rgb(inverted, inverted, inverted))
            }
        }
        return output
    }
}
