package com.clahbot.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The builder-strip classifier against pixels decoded from REAL game frames.
 *
 * Each fixture under `src/test/resources/builder` is a 185x95 crop of the bottom
 * right builder strip of a 1360x612 village recording, cut at 1:1 scale so the
 * test uses the same pixels the game draws. The `.png` next to it is the same
 * crop for a human to look at; the `.rgb` is that image exported as
 * `int32 width, int32 height, then RGB triplets` because the Android unit-test
 * classpath has no `java.awt`/`javax.imageio` to decode a PNG.
 *
 * The crop is identical for every image, so the builder slot's `free/total`
 * badge is always at (40,55)-(70,75) and the 2x variant (a bicubic upscale,
 * standing in for a 2720x1224 device frame) puts it at (80,110)-(140,150).
 *
 * Why this exists: the previous classifier was a colour heuristic that mixed
 * the icon with the foliage behind it and reported a regular `5/5` slot as the
 * goblin builder on the real phone. These tests pin the replacement to the
 * frames where that happened.
 */
class BuilderFaceSearchTest {

    private data class Frame(val pixels: IntArray, val width: Int, val height: Int)

    private fun load(name: String): Frame {
        val bytes = javaClass.getResourceAsStream("/builder/$name.rgb")?.use { it.readBytes() }
            ?: throw AssertionError("missing test resource /builder/$name.rgb")
        if (bytes.size < 8) throw AssertionError("truncated fixture /builder/$name.rgb")
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        val width = buffer.int
        val height = buffer.int
        if (width <= 0 || height <= 0 || bytes.size < 8 + width * height * 3) {
            throw AssertionError("bad fixture header in /builder/$name.rgb")
        }
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Frame(pixels, width, height)
    }

    /** The builder slot's badge in the standard 1:1 crop. */
    private fun classifySlot(frame: Frame) = BuilderFaceSearch.search(
        pixels = frame.pixels,
        width = frame.width,
        height = frame.height,
        badgeCentreX = 55,
        badgeTop = 55,
        badgeHeight = 20,
        badgeLabel = "slot1"
    )

    @Test
    fun `a real regular builder slot is a regular face`() {
        val hit = classifySlot(load("builder-normal"))

        assertNotNull(hit)
        assertEquals(BuilderFaceKind.REGULAR, hit!!.kind)
        assertTrue("scores must be logged: ${hit.scores.format()}", hit.geometry.startsWith("side="))
        assertTrue("normal score must be high: ${hit.scores.normal}", hit.scores.normal > 0.70f)
        assertTrue("margin must be decisive: ${hit.scores.margin}", hit.scores.margin > 0.20f)
    }

    @Test
    fun `a second real regular builder slot is a regular face`() {
        val hit = classifySlot(load("builder-normal2"))

        assertNotNull(hit)
        assertEquals(BuilderFaceKind.REGULAR, hit!!.kind)
    }

    @Test
    fun `a real goblin builder slot is a goblin face`() {
        val hit = classifySlot(load("builder-goblin"))

        assertNotNull(hit)
        assertEquals(BuilderFaceKind.GOBLIN, hit!!.kind)
        assertTrue("goblin score must be high: ${hit.scores.goblin}", hit.scores.goblin > 0.70f)
    }

    @Test
    fun `a second real goblin builder slot is a goblin face`() {
        val hit = classifySlot(load("builder-goblin2"))

        assertNotNull(hit)
        assertEquals(BuilderFaceKind.GOBLIN, hit!!.kind)
    }

    /**
     * One real frame sits between the two faces (the icon is partly covered by
     * the dimming banner). The classifier must answer "uncertain", never a
     * builder count it cannot support.
     */
    @Test
    fun `an ambiguous real slot stays uncertain`() {
        val hit = classifySlot(load("builder-ambiguous"))

        assertNotNull(hit)
        assertNull(hit!!.kind)
        assertTrue("margin was ${hit.scores.margin}", hit.scores.margin < BuilderFaceMatcher.MIN_MARGIN)
    }

    /**
     * The same frame at device resolution (2720x1224 game frames are exactly 2x
     * the reference recordings) with the badge box scaled the same way. Geometry
     * is derived from the badge height, so no pixel constant has to change.
     */
    @Test
    fun `the same slot is read at device resolution`() {
        val frame = load("builder-normal-2x")

        val hit = BuilderFaceSearch.search(
            pixels = frame.pixels,
            width = frame.width,
            height = frame.height,
            badgeCentreX = 110,
            badgeTop = 110,
            badgeHeight = 40,
            badgeLabel = "slot1-2x"
        )

        assertNotNull(hit)
        assertEquals(BuilderFaceKind.REGULAR, hit!!.kind)
    }

    @Test
    fun `a badge too small to hold an icon is not guessed`() {
        val frame = load("builder-normal")

        assertNull(
            BuilderFaceSearch.search(frame.pixels, frame.width, frame.height, 55, 55, 4)
        )
    }

    @Test
    fun `a truncated buffer is not read`() {
        assertNull(BuilderFaceSearch.search(IntArray(10), 40, 40, 20, 20, 20))
    }
}
