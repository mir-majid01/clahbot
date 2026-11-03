package com.clahbot.walls

import com.clahbot.game.TextLine
import com.clahbot.ocr.IconColorSampler
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallDialogParserTest {

    private val frameW = 1000
    private val frameH = 500

    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        TextLine(text, left, top, right, bottom)

    @Test
    fun `the large paid price wins over the small struck-through original`() {
        val lines = listOf(
            line("1,200,000", 0.40f, 0.42f, 0.52f, 0.46f), // struck-through original
            line("4,000,000", 0.38f, 0.50f, 0.58f, 0.58f) // price actually paid
        )
        val parsed = WallDialogParser.largePrice(
            lines,
            frameW,
            frameH,
            WallResource.GOLD,
            IconColorSampler { ResourceKind.GOLD }
        )
        assertEquals(4_000_000L, parsed?.amount)
        assertEquals(ResourceKind.GOLD, parsed?.icon)
    }

    @Test
    fun `a lower price is still parsed and reported by the caller`() {
        val lines = listOf(line("3,800,000", 0.38f, 0.50f, 0.58f, 0.58f))
        val parsed = WallDialogParser.largePrice(
            lines,
            frameW,
            frameH,
            WallResource.GOLD,
            IconColorSampler { ResourceKind.GOLD }
        )
        assertEquals(3_800_000L, parsed?.amount)
        assertEquals(
            WallMath.PriceVerdict.LOWER,
            WallMath.priceCheck(4_000_000, parsed!!.amount)
        )
    }

    @Test
    fun `the resource icon decides which price is accepted`() {
        val lines = listOf(
            line("4,000,000", 0.38f, 0.50f, 0.58f, 0.58f),
            line("4,000,000", 0.38f, 0.62f, 0.58f, 0.70f)
        )
        val sampler = IconColorSampler { box ->
            if (box.top < 300) ResourceKind.GOLD else ResourceKind.ELIXIR
        }
        assertEquals(
            4_000_000L,
            WallDialogParser.largePrice(lines, frameW, frameH, WallResource.GOLD, sampler)?.amount
        )
        assertEquals(
            4_000_000L,
            WallDialogParser.largePrice(lines, frameW, frameH, WallResource.ELIXIR, sampler)?.amount
        )
    }

    @Test
    fun `a blue wall ring price is never returned`() {
        val lines = listOf(line("80,000", 0.38f, 0.50f, 0.50f, 0.58f))
        // A blue button never classifies as gold or elixir.
        val parsed = WallDialogParser.largePrice(
            lines,
            frameW,
            frameH,
            WallResource.GOLD,
            IconColorSampler { null }
        )
        assertNull(parsed)
    }

    @Test
    fun `a gem price with letters is ignored`() {
        val lines = listOf(line("400 gems", 0.38f, 0.50f, 0.58f, 0.58f))
        val parsed = WallDialogParser.largePrice(
            lines,
            frameW,
            frameH,
            WallResource.GOLD,
            IconColorSampler { ResourceKind.GOLD }
        )
        assertNull(parsed)
    }

    @Test
    fun `text outside the dialog is ignored`() {
        val lines = listOf(line("4,000,000", 0.38f, 0.05f, 0.58f, 0.12f))
        val parsed = WallDialogParser.largePrice(
            lines,
            frameW,
            frameH,
            WallResource.GOLD,
            IconColorSampler { ResourceKind.GOLD }
        )
        assertNull(parsed)
    }

    @Test
    fun `matching gold price never verifies as elixir`() {
        val lines = listOf(line("4,000,000", 0.38f, 0.50f, 0.58f, 0.58f))
        val parsed = WallDialogParser.largePrice(
            lines,
            frameW,
            frameH,
            WallResource.ELIXIR,
            IconColorSampler { ResourceKind.GOLD }
        )
        assertNull(parsed)
    }
}
