package com.clahbot.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the template correlation. No Android type is involved, so the
 * decision itself is fully covered here; the frame geometry that feeds it is
 * covered by [BuilderFaceSearchTest] with pixels decoded from real screenshots.
 */
class BuilderFaceMatcherTest {

    /** The reference template itself is the best possible match for itself. */
    @Test
    fun `a patch equal to the template scores one and is decisive`() {
        val scores = BuilderFaceMatcher.score(BuilderFaceTemplates.NORMAL)

        assertEquals(1f, scores.normal, 0.001f)
        assertEquals(BuilderFaceKind.REGULAR, scores.bestKind)
        assertTrue(scores.isDecisive)
        assertEquals(BuilderFaceKind.REGULAR, BuilderFaceMatcher.decide(scores))
    }

    @Test
    fun `the goblin template is a decisive goblin`() {
        val scores = BuilderFaceMatcher.score(BuilderFaceTemplates.GOBLIN)

        assertEquals(1f, scores.goblin, 0.001f)
        assertEquals(BuilderFaceKind.GOBLIN, scores.bestKind)
        assertTrue(scores.isDecisive)
        assertEquals(BuilderFaceKind.GOBLIN, BuilderFaceMatcher.decide(scores))
    }

    @Test
    fun `the laboratory icon is never reported as a builder`() {
        val scores = BuilderFaceMatcher.score(BuilderFaceTemplates.LAB)

        assertEquals(1f, scores.lab, 0.001f)
        // The lab slot is not a builder slot: the answer must be null, which the
        // caller turns into "uncertain", never into a builder count.
        assertNull(scores.bestKind)
        assertFalse(scores.isDecisive)
        assertNull(BuilderFaceMatcher.decide(scores))
    }

    @Test
    fun `a flat patch correlates with nothing`() {
        val flat = IntArray(BuilderFaceTemplates.GRID * BuilderFaceTemplates.GRID) { 0x808080 }

        val scores = BuilderFaceMatcher.score(flat)

        assertEquals(0f, scores.normal, 0.0001f)
        assertEquals(0f, scores.goblin, 0.0001f)
        assertEquals(0f, scores.lab, 0.0001f)
        assertFalse(scores.isDecisive)
        assertNull(BuilderFaceMatcher.decide(scores))
    }

    /**
     * The strip is drawn over village artwork and inside a dimming banner, so the
     * same icon can be brighter or darker between frames. Normalised correlation
     * ignores that; a raw pixel comparison would not.
     */
    @Test
    fun `brightness and contrast do not change the decision`() {
        val bright = scale(BuilderFaceTemplates.NORMAL, 1.25f, -10)
        val dim = scale(BuilderFaceTemplates.NORMAL, 0.6f, 20)

        assertEquals(BuilderFaceKind.REGULAR, BuilderFaceMatcher.decide(BuilderFaceMatcher.score(bright)))
        assertEquals(BuilderFaceKind.REGULAR, BuilderFaceMatcher.decide(BuilderFaceMatcher.score(dim)))
    }

    /**
     * A patch halfway between the two faces must not be forced into either
     * answer: the margin gate is what keeps a half-covered icon from inventing a
     * builder count.
     */
    @Test
    fun `a patch between both faces stays undecided`() {
        val blended = average(BuilderFaceTemplates.NORMAL, BuilderFaceTemplates.GOBLIN)

        val scores = BuilderFaceMatcher.score(blended)

        assertTrue("margin must be small, was ${scores.margin}", scores.margin < BuilderFaceMatcher.MIN_MARGIN)
        assertNull(BuilderFaceMatcher.decide(scores))
    }

    @Test
    fun `the score gate rejects a weak winner and a narrow margin`() {
        // Below the absolute gate: an icon-shaped patch that is only vaguely face
        // like must not become a builder count.
        assertNull(BuilderFaceMatcher.decide(BuilderFaceMatcher.Scores(0.61f, 0.50f, 0.10f)))
        // Above the absolute gate but too close to the runner-up: a half-covered
        // or half-drawn icon.
        assertNull(BuilderFaceMatcher.decide(BuilderFaceMatcher.Scores(0.70f, 0.62f, 0.10f)))
        // A clear win both ways is the only accepted answer.
        assertEquals(
            BuilderFaceKind.REGULAR,
            BuilderFaceMatcher.decide(BuilderFaceMatcher.Scores(0.70f, 0.50f, 0.10f))
        )
        assertEquals(
            BuilderFaceKind.GOBLIN,
            BuilderFaceMatcher.decide(BuilderFaceMatcher.Scores(0.50f, 0.70f, 0.10f))
        )
    }

    @Test
    fun `geometry of the exposed gate constants is unchanged`() {
        // The sampler's search window is sized from these; a silent change here
        // would cut the search short on a real frame.
        assertEquals(1.33f, BuilderFaceSearch.FACE_SIDE_FACTOR, 0.0001f)
        assertEquals(1.15f, BuilderFaceSearch.MAX_SCALE, 0.0001f)
        assertEquals(3, BuilderFaceSearch.SCALES.size)
        assertEquals(0.62f, BuilderFaceMatcher.MIN_SCORE, 0.0001f)
        assertEquals(0.12f, BuilderFaceMatcher.MIN_MARGIN, 0.0001f)
    }

    private fun scale(patch: IntArray, factor: Float, offset: Int): IntArray =
        IntArray(patch.size) { i ->
            fun channel(shift: Int): Int {
                val v = ((patch[i] shr shift) and 0xFF) * factor + offset
                return v.toInt().coerceIn(0, 255)
            }
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

    private fun average(a: IntArray, b: IntArray): IntArray =
        IntArray(a.size) { i ->
            var out = 0
            for (shift in intArrayOf(16, 8, 0)) {
                val va = (a[i] shr shift) and 0xFF
                val vb = (b[i] shr shift) and 0xFF
                out = out or (((va + vb) / 2) shl shift)
            }
            out
        }
}
