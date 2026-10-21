package com.clahbot.vision

import android.graphics.Bitmap
import android.graphics.Color

class RedLineDetector {

    fun detect(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        var redPixels = 0
        var total = 0
        val step = 6

        for (y in (h * 0.2).toInt() until (h * 0.8).toInt() step step) {
            for (x in (w * 0.1).toInt() until (w * 0.9).toInt() step step) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (r > 180 && g < 80 && b < 80) redPixels++
                total++
            }
        }

        return total > 0 && redPixels.toFloat() / total > 0.02f
    }
}
