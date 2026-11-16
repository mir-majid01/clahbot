package com.clahbot.ocr

/**
 * Normalised cross-correlation matcher for the builder-strip face icons.
 *
 * Pure JVM: it takes a 12x12 grid of packed RGB samples and returns how well
 * that patch matches the regular builder, the goblin builder and the laboratory
 * icon. No Android type is involved, so the whole decision is unit-testable.
 * Extracting the patch from a frame lives in [BitmapBuilderFaceSampler].
 *
 * The correlation is computed over all three channels of the whole patch at
 * once, after each side is standardised to mean 0 / deviation 1. That makes it
 * insensitive to overall brightness and contrast (a night-mode village, a
 * banner dimming the strip) while still comparing shape.
 */
object BuilderFaceMatcher {

    /** Minimum correlation the winning template must reach to be believable. */
    const val MIN_SCORE = 0.62f

    /** Minimum gap between the winner and the runner-up. */
    const val MIN_MARGIN = 0.12f

    /** Correlation of one patch against the three reference icons. */
    data class Scores(val normal: Float, val goblin: Float, val lab: Float) {

        val best: Float get() = maxOf(normal, goblin, lab)

        /**
         * The icon the patch looks most like, or null when the laboratory icon
         * wins: that slot is never a builder slot, so "looks like the lab" must
         * not be reported as a regular builder.
         */
        val bestKind: BuilderFaceKind?
            get() = when {
                normal >= goblin && normal >= lab -> BuilderFaceKind.REGULAR
                goblin >= normal && goblin >= lab -> BuilderFaceKind.GOBLIN
                else -> null
            }

        val runnerUp: Float
            get() = when (bestKind) {
                BuilderFaceKind.REGULAR -> maxOf(goblin, lab)
                BuilderFaceKind.GOBLIN -> maxOf(normal, lab)
                null -> maxOf(normal, goblin)
            }

        val margin: Float get() = best - runnerUp

        /** A clear win: high enough and far enough ahead of the runner-up. */
        val isDecisive: Boolean
            get() = bestKind != null && best >= MIN_SCORE && margin >= MIN_MARGIN

        /** One-line diagnostic for the persistent log. */
        fun format(): String =
            "n=${f(normal)} g=${f(goblin)} l=${f(lab)} margin=${f(margin)}"
    }

    /**
     * Area-averages a [side]x[side] square of [src] (packed ARGB, row-major with
     * [srcWidth] stride) down to a [BuilderFaceTemplates.GRID] square patch of
     * packed RGB.
     *
     * The square is clamped inside the buffer, so a slot near the frame edge
     * still produces a full patch instead of throwing.
     */
    fun averagePatch(
        src: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        left: Int,
        top: Int,
        side: Int
    ): IntArray {
        val grid = BuilderFaceTemplates.GRID
        val out = IntArray(grid * grid)
        if (srcWidth <= 0 || srcHeight <= 0 || side <= 0) return out
        val x0 = left.coerceIn(0, (srcWidth - 1).coerceAtLeast(0))
        val y0 = top.coerceIn(0, (srcHeight - 1).coerceAtLeast(0))
        val x1 = (left + side).coerceIn(x0 + 1, srcWidth)
        val y1 = (top + side).coerceIn(y0 + 1, srcHeight)
        val spanX = x1 - x0
        val spanY = y1 - y0
        for (gy in 0 until grid) {
            val cellTop = y0 + (gy * spanY) / grid
            val cellBottom = (y0 + ((gy + 1) * spanY) / grid).coerceAtLeast(cellTop + 1)
            for (gx in 0 until grid) {
                val cellLeft = x0 + (gx * spanX) / grid
                val cellRight = (x0 + ((gx + 1) * spanX) / grid).coerceAtLeast(cellLeft + 1)
                var sr = 0L
                var sg = 0L
                var sb = 0L
                var n = 0
                for (y in cellTop until minOf(cellBottom, srcHeight)) {
                    val row = y * srcWidth
                    for (x in cellLeft until minOf(cellRight, srcWidth)) {
                        val p = src[row + x]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        n++
                    }
                }
                if (n == 0) n = 1
                val r = (sr / n).toInt()
                val g = (sg / n).toInt()
                val b = (sb / n).toInt()
                out[gy * grid + gx] = (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** Scores one patch against every reference icon. */
    fun score(patch: IntArray): Scores {
        require(patch.size == BuilderFaceTemplates.GRID * BuilderFaceTemplates.GRID) {
            "patch must be ${BuilderFaceTemplates.GRID}x${BuilderFaceTemplates.GRID}"
        }
        return Scores(
            normal = ncc(patch, BuilderFaceTemplates.NORMAL),
            goblin = ncc(patch, BuilderFaceTemplates.GOBLIN),
            lab = ncc(patch, BuilderFaceTemplates.LAB)
        )
    }

    /**
     * The template decision, or null when the patch is not clearly one of the
     * two builder faces. A null answer makes the caller report "uncertain"
     * instead of picking a builder count.
     */
    fun decide(scores: Scores): BuilderFaceKind? =
        if (scores.isDecisive) scores.bestKind else null

    /**
     * Normalised cross-correlation of two equally sized packed-RGB patches.
     *
     * Both sides are standardised by their own mean and standard deviation over
     * all channels, then the mean products are summed. A flat patch (no
     * variation) scores 0 rather than producing a division by zero.
     */
    fun ncc(a: IntArray, b: IntArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        val channels = n * 3
        val valuesA = FloatArray(channels)
        val valuesB = FloatArray(channels)
        for (i in 0 until n) {
            val pa = a[i]
            val pb = b[i]
            valuesA[i * 3] = ((pa shr 16) and 0xFF).toFloat()
            valuesA[i * 3 + 1] = ((pa shr 8) and 0xFF).toFloat()
            valuesA[i * 3 + 2] = (pa and 0xFF).toFloat()
            valuesB[i * 3] = ((pb shr 16) and 0xFF).toFloat()
            valuesB[i * 3 + 1] = ((pb shr 8) and 0xFF).toFloat()
            valuesB[i * 3 + 2] = (pb and 0xFF).toFloat()
        }
        return normalisedDot(valuesA, valuesB)
    }

    private fun normalisedDot(a: FloatArray, b: FloatArray): Float {
        var meanA = 0f
        var meanB = 0f
        for (i in a.indices) {
            meanA += a[i]
            meanB += b[i]
        }
        meanA /= a.size
        meanB /= b.size
        var varA = 0f
        var varB = 0f
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            varA += da * da
            varB += db * db
        }
        val sdA = kotlin.math.sqrt(varA / a.size)
        val sdB = kotlin.math.sqrt(varB / b.size)
        if (sdA < 1e-6f || sdB < 1e-6f) return 0f
        var dot = 0f
        for (i in a.indices) {
            dot += ((a[i] - meanA) / sdA) * ((b[i] - meanB) / sdB)
        }
        return dot / a.size
    }

    private fun f(value: Float): String =
        String.format(java.util.Locale.US, "%.2f", value)
}
