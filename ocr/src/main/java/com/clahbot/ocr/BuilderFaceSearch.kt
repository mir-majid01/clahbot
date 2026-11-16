package com.clahbot.ocr

/**
 * The bitmap-independent half of the builder-strip face classifier.
 *
 * [BitmapBuilderFaceSampler] only extracts a small pixel region from the frame;
 * every geometric and scoring decision lives here, over a plain ARGB buffer.
 * That split exists so the decision can be unit-tested against pixels decoded
 * from real game screenshots on the JVM, without an Android graphics device.
 *
 * Geometry is derived from the `free/total` badge, never from absolute pixels:
 * the icon is a square whose side is a multiple of the badge text height and
 * whose bottom edge overlaps the top of the badge. A small search window absorbs
 * sub-pixel drift between the OCR box and the drawn icon, and three scale
 * variants cover the slightly different icon sizes of the village HUD and the
 * building list.
 */
object BuilderFaceSearch {

    /** The decision for one badge: the icon, its scores and the geometry used. */
    data class Hit(
        val kind: BuilderFaceKind?,
        val scores: BuilderFaceMatcher.Scores,
        val geometry: String
    )

    /**
     * Scores the icon above a badge.
     *
     * [pixels] is a row-major ARGB buffer of [width] x [height]; the badge is
     * given in the same coordinates. Returns null only when the badge is too
     * small to locate an icon, which the caller must treat as uncertain.
     */
    fun search(
        pixels: IntArray,
        width: Int,
        height: Int,
        badgeCentreX: Int,
        badgeTop: Int,
        badgeHeight: Int,
        badgeLabel: String = ""
    ): Hit? {
        if (width < 6 || height < 6) return null
        if (pixels.size < width * height) return null
        if (badgeHeight < MIN_BADGE_HEIGHT) return null

        val baseSide = (badgeHeight * FACE_SIDE_FACTOR).toInt()
        if (baseSide < MIN_FACE_SIDE) return null
        val bottom = badgeTop + (badgeHeight * FACE_BOTTOM_OVERLAP).toInt()
        val search = (baseSide * SEARCH_FRACTION).toInt().coerceAtLeast(2)

        var bestNormal = -2f
        var bestGoblin = -2f
        var bestLab = -2f
        var bestSide = baseSide
        var bestDx = 0
        var bestDy = 0
        for (scale in SCALES) {
            val side = (baseSide * scale).toInt()
            if (side < MIN_FACE_SIDE) continue
            var dy = -search
            while (dy <= search) {
                var dx = -search
                while (dx <= search) {
                    val patchLeft = badgeCentreX - side / 2 + dx
                    val patchTop = bottom - side + dy
                    val patch = BuilderFaceMatcher.averagePatch(
                        pixels, width, height, patchLeft, patchTop, side
                    )
                    val scores = BuilderFaceMatcher.score(patch)
                    if (scores.normal > bestNormal) {
                        bestNormal = scores.normal
                        if (scores.normal >= scores.goblin && scores.normal >= scores.lab) {
                            bestSide = side
                            bestDx = dx
                            bestDy = dy
                        }
                    }
                    if (scores.goblin > bestGoblin) bestGoblin = scores.goblin
                    if (scores.lab > bestLab) bestLab = scores.lab
                    dx += SEARCH_STEP
                }
                dy += SEARCH_STEP
            }
        }

        val scores = BuilderFaceMatcher.Scores(bestNormal, bestGoblin, bestLab)
        val geometry = "side=${bestSide}px scaleOf=${
            String.format(java.util.Locale.US, "%.2f", bestSide.toFloat() / baseSide)
        } dx=$bestDx dy=$bestDy badge=$badgeLabel".trim()
        return Hit(BuilderFaceMatcher.decide(scores), scores, geometry)
    }

    /** The icon square is this multiple of the badge text height. */
    const val FACE_SIDE_FACTOR = 1.33f

    /** The icon's bottom edge reaches this far down into the badge text. */
    const val FACE_BOTTOM_OVERLAP = 0.15f

    /** Search drift around the expected icon centre, as a share of its side. */
    const val SEARCH_FRACTION = 0.35f

    /** Search step in pixels; icon features are several pixels wide. */
    const val SEARCH_STEP = 2

    /** A badge shorter than this cannot locate an icon reliably. */
    const val MIN_BADGE_HEIGHT = 8

    /** An icon smaller than this has too little shape to correlate. */
    const val MIN_FACE_SIDE = 10

    /** Largest scale variant; matches the search window the sampler extracts. */
    const val MAX_SCALE = 1.15f

    /** Scale variants: the list view draws slightly smaller icons than the HUD. */
    val SCALES = floatArrayOf(0.85f, 1.0f, 1.15f)
}
