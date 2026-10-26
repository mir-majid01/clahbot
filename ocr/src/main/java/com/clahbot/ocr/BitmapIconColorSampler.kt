package com.clahbot.ocr

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Samples the resource icon immediately to the right of a row number.
 * Neutral text, panel art and the battlefield are ignored; the saturated coin
 * or elixir blob wins. Loot rows without a recognised icon are dropped rather
 * than assigned by position, which also excludes the Sour Elixir panel.
 */
class BitmapIconColorSampler(private val bitmap: Bitmap) : IconColorSampler {

    override fun classify(numberBox: OcrBox): ResourceKind? {
        val sampleHeight = (numberBox.height * 1.4f).toInt().coerceIn(24, 96)
        val sampleWidth = (numberBox.height * 2.0f).toInt().coerceIn(32, 150)
        val gap = maxOf(6, numberBox.height / 4)
        val left = (numberBox.right + gap).coerceAtLeast(0)
        val top = (numberBox.centerY - sampleHeight / 2).coerceAtLeast(0)
        val right = (left + sampleWidth).coerceAtMost(bitmap.width)
        val bottom = (top + sampleHeight).coerceAtMost(bitmap.height)
        if (right - left < 3 || bottom - top < 3) return null

        var gold = 0
        var elixir = 0
        var dark = 0
        val hsv = FloatArray(3)
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 128) continue
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                val max = maxOf(red, green, blue)
                val min = minOf(red, green, blue)
                val saturation = if (max == 0) 0f else (max - min) / max.toFloat()
                val value = max / 255f
                if (value < 0.08f || saturation < 0.18f) continue
                Color.RGBToHSV(red, green, blue, hsv)
                val hue = hsv[0]
                when {
                    hue in 25f..70f && saturation >= 0.40f && value >= 0.35f -> gold++
                    hue in 275f..325f && saturation >= 0.40f && value >= 0.42f -> elixir++
                    hue in 250f..330f && saturation >= 0.20f && value < 0.42f -> dark++
                    value < 0.42f && saturation >= 0.20f -> dark++
                }
            }
        }

        val best = maxOf(gold, elixir, dark)
        if (best < 12) return null
        val second = when (best) {
            gold -> maxOf(elixir, dark)
            elixir -> maxOf(gold, dark)
            else -> maxOf(gold, elixir)
        }
        if (best < second + 3) return null
        return when (best) {
            gold -> ResourceKind.GOLD
            elixir -> ResourceKind.ELIXIR
            else -> ResourceKind.DARK
        }
    }
}
