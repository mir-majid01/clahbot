package com.clahbot.walls

import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry here is measured from the user's own screenshots: the plain
 * village puts the gold digits near 85 % of the width, and the same digits move
 * to about 55 % as soon as the Buildings & Heroes panel is open — which is
 * exactly when a wall macro needs the numbers.
 */
class WallCountersTest {

    private val width = 1000
    private val height = 500

    private fun line(text: String, centerX: Float, centerY: Float) = OcrLine(
        text = text,
        box = OcrBox(
            left = (centerX * width - 60).toInt(),
            top = (centerY * height - 12).toInt(),
            right = (centerX * width + 60).toInt(),
            bottom = (centerY * height + 12).toInt()
        ),
        elements = emptyList()
    )

    /** Icons in the same order the frames arrive, so the test pins the pairing. */
    private fun classifier(vararg kinds: ResourceKind?): (OcrBox) -> ResourceKind? {
        var index = 0
        return { _ -> kinds.getOrElse(index++) { null } }
    }

    @Test
    fun `reads the plain village counters by their icons`() {
        val page = listOf(
            line("4 212 343", 0.89f, 0.06f),
            line("4 234 695", 0.89f, 0.12f),
            line("29 139", 0.89f, 0.18f)
        )

        val reading = WallCounters.read(page, width, height, classifier(ResourceKind.GOLD, ResourceKind.ELIXIR, ResourceKind.DARK))

        assertEquals(4_212_343L, reading.gold)
        assertEquals(4_234_695L, reading.elixir)
        assertEquals(3, reading.rows)
        assertEquals(2, reading.classified)
    }

    @Test
    fun `reads the counters shifted left by the build panel`() {
        val page = listOf(
            line("4 501 599", 0.55f, 0.06f),
            line("8 009 367", 0.55f, 0.12f)
        )

        val reading = WallCounters.read(page, width, height, classifier(ResourceKind.GOLD, ResourceKind.ELIXIR))

        assertEquals(4_501_599L, reading.gold)
        assertEquals(8_009_367L, reading.elixir)
    }

    @Test
    fun `a village without a dark row still gives gold and elixir`() {
        val page = listOf(
            line("1 250 000", 0.89f, 0.06f),
            line("1 300 000", 0.89f, 0.12f)
        )

        val reading = WallCounters.read(page, width, height, classifier(ResourceKind.GOLD, ResourceKind.ELIXIR))

        assertEquals(1_250_000L, reading.gold)
        assertEquals(1_300_000L, reading.elixir)
    }

    @Test
    fun `row order never decides which resource a number is`() {
        val page = listOf(
            line("7 000 000", 0.89f, 0.12f),
            line("1 000 000", 0.89f, 0.06f)
        )

        val reading = WallCounters.read(page, width, height, classifier(ResourceKind.ELIXIR, ResourceKind.GOLD))

        assertEquals(1_000_000L, reading.gold)
        assertEquals(7_000_000L, reading.elixir)
    }

    @Test
    fun `upgrade prices lower on the screen are not counters`() {
        val page = listOf(
            line("1 500 000", 0.66f, 0.78f),
            line("1 500 000", 0.76f, 0.78f)
        )

        val reading = WallCounters.read(page, width, height, classifier(ResourceKind.GOLD, ResourceKind.ELIXIR))

        assertFalse(reading.anything)
        assertEquals(0, reading.rows)
    }

    @Test
    fun `numbers in the left half are not counters`() {
        val page = listOf(line("213", 0.10f, 0.05f))

        val reading = WallCounters.read(page, width, height, classifier(ResourceKind.GOLD))

        assertFalse(reading.anything)
    }

    @Test
    fun `an unclassified icon is reported rather than guessed`() {
        val page = listOf(line("4 501 599", 0.55f, 0.06f))

        val reading = WallCounters.read(page, width, height, classifier(null))

        assertFalse(reading.anything)
        assertEquals(1, reading.rows)
        assertEquals("counters unreadable", reading.describe())
    }

    @Test
    fun `an empty frame says so`() {
        val reading = WallCounters.read(emptyList(), width, height, classifier())

        assertEquals("no counters on screen", reading.describe())
        assertEquals(0, reading.rows)
    }

    @Test
    fun `a frame with no size cannot produce a reading`() {
        val reading = WallCounters.read(listOf(line("1 000", 0.5f, 0.05f)), 0, 0, classifier(ResourceKind.GOLD))

        assertFalse(reading.anything)
    }

    @Test
    fun `the status line names the resource that is missing`() {
        val goldOnly = WallCounters.Reading(gold = 1_500_000L)
        assertTrue(goldOnly.describe(), goldOnly.describe().contains("elixir not read"))

        val both = WallCounters.Reading(gold = 1_500_000L, elixir = 2_000_000L)
        assertEquals("gold 1M · elixir 2M", both.describe())
    }
}
