package com.clahbot.results

import android.graphics.Bitmap
import java.util.Locale

/**
 * A cheap 64-bit average hash of the whole result screen. It is used only to
 * recognise the same screen on the next poll, never to identify loot.
 */
object ScreenHash {

    fun fromBitmap(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        if (small !== bitmap) small.recycle()
        val luma = IntArray(64) { index ->
            val color = pixels[index]
            val red = (color shr 16) and 0xff
            val green = (color shr 8) and 0xff
            val blue = color and 0xff
            (red * 299 + green * 587 + blue * 114) / 1000
        }
        return fromLuma(luma)
    }

    fun fromLuma(luma: IntArray): String {
        require(luma.size == 64) { "expected 64 luma samples" }
        val average = luma.sum().toDouble() / luma.size
        var bits = 0L
        for (index in luma.indices) {
            if (luma[index] >= average) bits = bits or (1L shl index)
        }
        return String.format(Locale.US, "%016x", bits)
    }
}
