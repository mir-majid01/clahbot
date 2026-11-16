package com.clahbot.ocr

import android.graphics.Bitmap

/**
 * Which builder a slot icon shows. The regular builder is a human face; the
 * Goblin Builder offer paints a green goblin face in the same slot.
 */
enum class BuilderFaceKind { REGULAR, GOBLIN }

/**
 * Classifies the face drawn immediately above a `free/total` fraction badge.
 *
 * The fraction text alone cannot say whether the slot belongs to a regular
 * builder or to the Gem-paid Goblin Builder: the real game shows `1/7` for the
 * goblin slot and `1/2` for the laboratory beside it, and a pure text reader
 * would happily call `1/2` a free regular builder. The face above the badge
 * answers that question, so [com.clahbot.walls.BuilderDetection] asks this
 * sampler before it turns a fraction into a builder count.
 *
 * Returns null (never a guess) when the icon is missing, covered, or does not
 * clearly match one of the reference faces. A null answer makes the caller
 * report "uncertain".
 */
fun interface BuilderFaceSampler {
    fun classify(fractionBox: OcrBox): BuilderFaceKind?
}

/**
 * Template-matching builder-face classifier.
 *
 * Earlier versions counted "skin" and "goblin green" pixels in an ellipse above
 * the badge. That cannot work: the strip is drawn on top of village artwork, so
 * the ellipse included green foliage, and on the real device a regular `5/5`
 * slot was reported as the goblin builder. This version compares the icon
 * against three reference templates cut from real frames (regular face, goblin
 * face, laboratory flask) and only answers when one of them is a clear win.
 *
 * Geometry comes from the fraction badge, not from absolute pixels, so the same
 * code works on the 1360x612 reference frames and on a 2720x1224 device frame:
 * the icon is a square whose side is a multiple of the badge height, sitting
 * directly above it. A small search window absorbs the sub-pixel drift between
 * OCR boxes and the drawn icon.
 */
class BitmapBuilderFaceSampler(private val bitmap: Bitmap) : BuilderFaceSampler {

    /** Scores of the last [classify] call, for the persistent scan log. */
    var lastScores: BuilderFaceMatcher.Scores? = null
        private set

    /** Geometry of the last [classify] call, for the persistent scan log. */
    var lastGeometry: String = ""
        private set

    /** One-line diagnostic of the last [classify] call. */
    fun diagnostic(): String {
        val scores = lastScores ?: return "no face sample"
        val decided = BuilderFaceMatcher.decide(scores)?.name ?: "UNCERTAIN"
        return "$decided ${scores.format()} ${lastGeometry}".trim()
    }

    override fun classify(fractionBox: OcrBox): BuilderFaceKind? {
        lastScores = null
        lastGeometry = ""
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        if (fractionBox.height < BuilderFaceSearch.MIN_BADGE_HEIGHT) return null

        val baseSide = (fractionBox.height * BuilderFaceSearch.FACE_SIDE_FACTOR).toInt()
        if (baseSide < BuilderFaceSearch.MIN_FACE_SIDE) return null
        val centreX = fractionBox.centerX
        val bottom =
            fractionBox.top + (fractionBox.height * BuilderFaceSearch.FACE_BOTTOM_OVERLAP).toInt()
        val search = (baseSide * BuilderFaceSearch.SEARCH_FRACTION).toInt().coerceAtLeast(2)

        // Only the window the search can reach is copied out of the frame: a full
        // frame copy would be megabytes per badge.
        val maxSide = (baseSide * BuilderFaceSearch.MAX_SCALE).toInt()
        val regionLeft = (centreX - maxSide / 2 - search - 1).coerceAtLeast(0)
        val regionTop = (bottom - maxSide - search - 1).coerceAtLeast(0)
        val regionRight = (centreX + maxSide / 2 + search + 1).coerceAtMost(bitmap.width)
        val regionBottom = (bottom + search + 1).coerceAtMost(bitmap.height)
        val regionWidth = regionRight - regionLeft
        val regionHeight = regionBottom - regionTop
        if (regionWidth < 6 || regionHeight < 6) return null

        val region = IntArray(regionWidth * regionHeight)
        runCatching {
            bitmap.getPixels(region, 0, regionWidth, regionLeft, regionTop, regionWidth, regionHeight)
        }.getOrElse { return null }

        val hit = BuilderFaceSearch.search(
            pixels = region,
            width = regionWidth,
            height = regionHeight,
            badgeCentreX = centreX - regionLeft,
            badgeTop = fractionBox.top - regionTop,
            badgeHeight = fractionBox.height,
            badgeLabel = fractionBox.format()
        ) ?: return null
        lastScores = hit.scores
        lastGeometry = hit.geometry
        return hit.kind
    }
}
