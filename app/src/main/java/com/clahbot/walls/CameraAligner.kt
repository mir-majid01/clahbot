package com.clahbot.walls

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Finds the stored camera landmark patch in a new game frame and returns the
 * measured offset in frame pixels. Used after every camera macro run so wall
 * tap points are corrected for small camera drift. Failures (score too low or
 * offset beyond the tolerance) abort the cycle rather than tapping blind.
 */
object CameraAligner {

    data class Alignment(val offsetX: Int, val offsetY: Int, val score: Float)

    /**
     * @param frame the current game frame
     * @param patch the stored landmark crop
     * @param expectedLeft/Top/Right/Bottom where the patch was taken, in frame
     *        fractions of the frame the patch was captured from
     * @param tolerancePx maximum accepted offset
     */
    fun align(
        frame: Bitmap,
        patch: Bitmap,
        expectedLeft: Float,
        expectedTop: Float,
        expectedRight: Float,
        expectedBottom: Float,
        tolerancePx: Int = TOLERANCE_PX
    ): Alignment? {
        if (patch.width < 8 || patch.height < 8) return null
        if (frame.width < patch.width || frame.height < patch.height) return null

        val searchLeft = (expectedLeft * frame.width - frame.width * SEARCH_FRACTION)
            .toInt().coerceAtLeast(0)
        val searchTop = (expectedTop * frame.height - frame.height * SEARCH_FRACTION)
            .toInt().coerceAtLeast(0)
        val searchRight = (expectedRight * frame.width + frame.width * SEARCH_FRACTION)
            .toInt().coerceAtMost(frame.width)
        val searchBottom = (expectedBottom * frame.height + frame.height * SEARCH_FRACTION)
            .toInt().coerceAtMost(frame.height)
        val regionWidth = searchRight - searchLeft
        val regionHeight = searchBottom - searchTop
        if (regionWidth < patch.width || regionHeight < patch.height) return null

        val region = Bitmap.createBitmap(frame, searchLeft, searchTop, regionWidth, regionHeight)
        val patchGray = grayscale(patch)

        // Coarse pass at 1/SEARCH_SCALE resolution, then a small full-res
        // refine around the winner keeps the search under ~50 ms.
        val scaledRegion = Bitmap.createScaledBitmap(
            region,
            regionWidth / SEARCH_SCALE,
            regionHeight / SEARCH_SCALE,
            true
        )
        val scaledPatch = Bitmap.createScaledBitmap(
            patch,
            patch.width / SEARCH_SCALE,
            patch.height / SEARCH_SCALE,
            true
        )
        val regionGray = grayscale(scaledRegion)
        val smallPatchGray = grayscale(scaledPatch)
        val sw = scaledRegion.width
        val sh = scaledRegion.height
        val pw = scaledPatch.width
        val ph = scaledPatch.height
        if (sw < pw || sh < ph) return null

        var bestScore = -1f
        var bestX = -1
        var bestY = -1
        for (y in 0..sh - ph) {
            for (x in 0..sw - pw) {
                val score = ncc(regionGray, sw, smallPatchGray, pw, ph, x, y)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
            }
        }
        if (bestScore < MIN_SCORE || bestX < 0) return null

        // Refine at full resolution within +/- REFINE_PX of the coarse hit.
        val fullRegionGray = grayscale(region)
        val coarseX = bestX * SEARCH_SCALE
        val coarseY = bestY * SEARCH_SCALE
        var refScore = -1f
        var refX = coarseX
        var refY = coarseY
        val fromX = (coarseX - REFINE_PX).coerceAtLeast(0)
        val toX = (coarseX + REFINE_PX).coerceAtMost(regionWidth - patch.width)
        val fromY = (coarseY - REFINE_PX).coerceAtLeast(0)
        val toY = (coarseY + REFINE_PX).coerceAtMost(regionHeight - patch.height)
        for (y in fromY..toY) {
            for (x in fromX..toX) {
                val score = ncc(fullRegionGray, regionWidth, patchGray, patch.width, patch.height, x, y)
                if (score > refScore) {
                    refScore = score
                    refX = x
                    refY = y
                }
            }
        }
        if (refScore < MIN_SCORE) return null

        val centreX = searchLeft + refX + patch.width / 2
        val centreY = searchTop + refY + patch.height / 2
        val expectedCentreX = ((expectedLeft + expectedRight) / 2f * frame.width).toInt()
        val expectedCentreY = ((expectedTop + expectedBottom) / 2f * frame.height).toInt()
        val offsetX = centreX - expectedCentreX
        val offsetY = centreY - expectedCentreY
        if (abs(offsetX) > tolerancePx || abs(offsetY) > tolerancePx) return null
        return Alignment(offsetX, offsetY, refScore)
    }

    /** Normalized cross-correlation on grayscale ints, computed directly. */
    private fun ncc(
        region: IntArray,
        regionWidth: Int,
        patch: IntArray,
        patchWidth: Int,
        patchHeight: Int,
        atX: Int,
        atY: Int
    ): Float {
        var sumR = 0L
        var sumP = 0L
        var count = 0
        for (py in 0 until patchHeight) {
            val rowR = (atY + py) * regionWidth + atX
            val rowP = py * patchWidth
            for (px in 0 until patchWidth) {
                sumR += region[rowR + px]
                sumP += patch[rowP + px]
            }
        }
        count = patchWidth * patchHeight
        val meanR = sumR.toDouble() / count
        val meanP = sumP.toDouble() / count
        var num = 0.0
        var denR = 0.0
        var denP = 0.0
        for (py in 0 until patchHeight) {
            val rowR = (atY + py) * regionWidth + atX
            val rowP = py * patchWidth
            for (px in 0 until patchWidth) {
                val r = region[rowR + px] - meanR
                val p = patch[rowP + px] - meanP
                num += r * p
                denR += r * r
                denP += p * p
            }
        }
        val den = kotlin.math.sqrt(denR) * kotlin.math.sqrt(denP)
        if (den <= 0.0) return 0f
        return (num / den).toFloat()
    }

    fun grayscale(bitmap: Bitmap): IntArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(pixels.size)
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            gray[index] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return gray
    }

    /** Crops the landmark patch from a frame at the given fractions. */
    fun cropPatch(frame: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val l = (left * frame.width).toInt().coerceIn(0, frame.width - 2)
        val t = (top * frame.height).toInt().coerceIn(0, frame.height - 2)
        val r = (right * frame.width).toInt().coerceIn(l + 2, frame.width)
        val b = (bottom * frame.height).toInt().coerceIn(t + 2, frame.height)
        return Bitmap.createBitmap(frame, l, t, r - l, b - t)
    }

    const val TOLERANCE_PX = 24
    private const val SEARCH_FRACTION = 0.10f
    private const val MIN_SCORE = 0.6f
    private const val SEARCH_SCALE = 4
    private const val REFINE_PX = 6
}
