package com.clahbot.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

class TemplateMatcher {

    data class MatchResult(
        val x: Int,
        val y: Int,
        val confidence: Float
    )

    fun match(bitmap: Bitmap, template: Bitmap, threshold: Float = 0.7f): MatchResult? {
        val step = 8
        var bestScore = 0f
        var bestX = 0
        var bestY = 0

        val tw = template.width
        val th = template.height
        if (tw > bitmap.width || th > bitmap.height) return null

        for (y in 0 until bitmap.height - th step step) {
            for (x in 0 until bitmap.width - tw step step) {
                val score = compareRegion(bitmap, template, x, y)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x + tw / 2
                    bestY = y + th / 2
                }
            }
        }

        return if (bestScore >= threshold) MatchResult(bestX, bestY, bestScore) else null
    }

    private fun compareRegion(bitmap: Bitmap, template: Bitmap, offsetX: Int, offsetY: Int): Float {
        val tw = template.width
        val th = template.height
        var match = 0
        var total = 0
        val sampleStep = 4

        for (ty in 0 until th step sampleStep) {
            for (tx in 0 until tw step sampleStep) {
                val bx = offsetX + tx
                val by = offsetY + ty
                if (bx >= bitmap.width || by >= bitmap.height) continue

                val bc = bitmap.getPixel(bx, by)
                val tc = template.getPixel(tx, ty)

                val dr = abs(Color.red(bc) - Color.red(tc))
                val dg = abs(Color.green(bc) - Color.green(tc))
                val db = abs(Color.blue(bc) - Color.blue(tc))

                if (dr + dg + db < 60) match++
                total++
            }
        }

        return if (total > 0) match.toFloat() / total else 0f
    }
}
