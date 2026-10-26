package com.clahbot.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * Nearest-neighbour digit classifier over 24x32 binary glyph templates.
 * The bundled sheet was extracted from labelled result screens; the matcher
 * normalises by glyph height and tolerates a small translation.
 */
class DigitTemplateStore private constructor(
    private val samples: List<List<FloatArray>>
) {

    data class Match(val digit: Int, val distance: Float, val margin: Float)

    fun classify(mask: FloatArray): Match? {
        val best = FloatArray(10) { Float.MAX_VALUE }
        for (digit in 0 until 10) {
            for (sample in samples[digit]) {
                val distance = distance(mask, sample)
                if (distance < best[digit]) best[digit] = distance
            }
        }
        var bestDigit = 0
        var bestDistance = Float.MAX_VALUE
        var secondDistance = Float.MAX_VALUE
        for (digit in 0 until 10) {
            val distance = best[digit]
            if (distance < bestDistance) {
                secondDistance = bestDistance
                bestDistance = distance
                bestDigit = digit
            } else if (distance < secondDistance) {
                secondDistance = distance
            }
        }
        val margin = secondDistance - bestDistance
        return if (bestDistance <= MAX_DISTANCE && margin >= MIN_MARGIN) {
            Match(bestDigit, bestDistance, margin)
        } else {
            null
        }
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var best = Float.MAX_VALUE
        for (dy in -1..1) {
            for (dx in -2..2) {
                var sum = 0f
                for (y in 0 until H) {
                    for (x in 0 until W) {
                        val ax = x + dx
                        val ay = y + dy
                        val av = if (ax in 0 until W && ay in 0 until H) {
                            a[ay * W + ax]
                        } else {
                            0f
                        }
                        sum += kotlin.math.abs(av - b[y * W + x])
                    }
                }
                val mean = sum / (H * W)
                if (mean < best) best = mean
            }
        }
        return best
    }

    companion object {
        const val W = 24
        const val H = 32
        private const val MAX_DISTANCE = 0.28f
        private const val MIN_MARGIN = 0.02f

        fun load(context: Context): DigitTemplateStore? {
            val sheet = context.assets.open("digits/digit_templates.png").use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return null
            val manifest = context.assets.open("digits/digit_templates.txt")
                .bufferedReader()
                .use { it.readLines() }
            if (sheet.width < W || sheet.height < H * 10) return null

            val samples = List(10) { mutableListOf<FloatArray>() }
            for (line in manifest) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size != 2) continue
                val digit = parts[0].toIntOrNull() ?: continue
                val count = parts[1].toIntOrNull() ?: continue
                if (digit !in 0..9) continue
                val maxCols = sheet.width / W
                for (col in 0 until count.coerceIn(0, maxCols)) {
                    samples[digit] += readCell(sheet, col * W, digit * H)
                }
            }
            if (samples.any { it.isEmpty() }) return null
            return DigitTemplateStore(samples)
        }

        private fun readCell(sheet: Bitmap, left: Int, top: Int): FloatArray {
            val mask = FloatArray(W * H)
            for (y in 0 until H) {
                for (x in 0 until W) {
                    val pixel = sheet.getPixel(left + x, top + y)
                    val value = Color.red(pixel)
                    mask[y * W + x] = if (value > 127) 1f else 0f
                }
            }
            return mask
        }
    }
}
