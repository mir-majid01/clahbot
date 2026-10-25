package com.clahbot.ocrlab

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Samples the icon immediately to the right of a loot number. The number box
 * ends just before the icon; white text and neutral shadows are ignored so the
 * saturated resource colour wins.
 */
class BitmapIconColorSampler(private val bitmap: Bitmap) : IconColorSampler {

    override fun classify(numberBox: OcrBox): ResourceKind? {
        val size = (numberBox.height * 0.9f).toInt().coerceIn(6, 28)
        val left = (numberBox.right + 4).coerceAtLeast(0)
        val top = (numberBox.centerY - size / 2).coerceAtLeast(0)
        val right = (left + size).coerceAtMost(bitmap.width)
        val bottom = (top + size).coerceAtMost(bitmap.height)
        if (right - left < 3 || bottom - top < 3) return null

        var gold = 0
        var elixir = 0
        var dark = 0
        var neutral = 0
        val hsv = FloatArray(3)
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 128) continue
                Color.RGBToHSV(Color.red(pixel), Color.green(pixel), Color.blue(pixel), hsv)
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]
                when {
                    value < 0.10f -> Unit
                    saturation < 0.18f -> neutral++
                    hue in 25f..70f && saturation >= 0.35f && value >= 0.35f -> gold++
                    hue in 270f..345f && saturation >= 0.30f && value >= 0.25f -> elixir++
                    value <= 0.38f && saturation >= 0.18f -> dark++
                    else -> neutral++
                }
            }
        }
        val best = maxOf(gold, elixir, dark)
        if (best < 3) return null
        return when (best) {
            gold -> ResourceKind.GOLD
            elixir -> ResourceKind.ELIXIR
            else -> ResourceKind.DARK
        }
    }
}
