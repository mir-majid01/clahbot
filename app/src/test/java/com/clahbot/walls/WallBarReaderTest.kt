package com.clahbot.walls

import com.clahbot.ocr.IconColorSampler
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.OcrPage
import com.clahbot.ocr.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WallBarReaderTest {

    private val frameW = 1000
    private val frameH = 500

    private fun page(vararg lines: Triple<String, OcrBox, Int>): OcrPage = OcrPage(
        width = frameW,
        height = frameH,
        lines = lines.map { (text, box, _) -> OcrLine(text, box, emptyList()) }
    )

    private fun box(left: Int, top: Int, right: Int, bottom: Int) = OcrBox(left, top, right, bottom)

    private fun reader(sampler: IconColorSampler, page: OcrPage) =
        WallBarReader(page, frameW, frameH, sampler)

    @Test
    fun `selection level is read from the wall caption`() {
        val bar = reader(
            IconColorSampler { null },
            page(
                Triple(
                    "Wall (Level 11)",
                    box(300, 420, 520, 450),
                    0
                )
            )
        )
        assertEquals(11, bar.selection()?.level)
    }

    @Test
    fun `upgrade and upgrade more are distinct buttons`() {
        val bar = reader(
            IconColorSampler { null },
            page(
                Triple("Upgrade", box(720, 430, 820, 460), 0),
                Triple("Upgrade More", box(540, 430, 640, 460), 0)
            )
        )
        assertNotNull(bar.upgradeButton())
        assertNotNull(bar.upgradeMoreButton())
    }

    @Test
    fun `plus buttons match exact counts only`() {
        val bar = reader(
            IconColorSampler { null },
            page(
                Triple("10", box(600, 430, 640, 460), 0),
                Triple("1", box(660, 430, 690, 460), 0),
                Triple("Level 9", box(300, 420, 420, 450), 0)
            )
        )
        assertNotNull(bar.plusButton(10))
        assertNotNull(bar.plusButton(1))
        assertNull(bar.plusButton(9))
    }

    @Test
    fun `multi-select count is read from the x chip`() {
        val bar = reader(
            IconColorSampler { null },
            page(
                Triple("x 3", box(700, 430, 740, 460), 0)
            )
        )
        assertEquals(3, bar.countLabel())
    }

    @Test
    fun `gold price is read when the icon is gold`() {
        val bar = reader(
            IconColorSampler { box -> if (box.left in 500..760) ResourceKind.GOLD else null },
            page(
                Triple("2,500,000", box(560, 430, 700, 460), 0),
                Triple("3,000,000", box(560, 380, 700, 405), 0) // outside the bar band
            )
        )
        val price = bar.price(WallResource.GOLD)
        assertEquals(2_500_000L, price?.amount)
        assertNull(bar.price(WallResource.ELIXIR))
    }

    @Test
    fun `a blue wall ring button never yields a price`() {
        val bar = reader(
            IconColorSampler { null },
            page(
                Triple("80,000", box(560, 430, 660, 460), 0)
            )
        )
        assertNull(bar.price(WallResource.GOLD))
        assertNull(bar.price(WallResource.ELIXIR))
    }

    @Test
    fun `level text never counts as a price`() {
        val bar = reader(
            IconColorSampler { ResourceKind.GOLD },
            page(
                Triple("Wall (Level 11)", box(300, 420, 520, 450), 0)
            )
        )
        assertNull(bar.price(WallResource.GOLD))
    }

    @Test
    fun `lines above the bar band are ignored`() {
        val bar = reader(
            IconColorSampler { ResourceKind.GOLD },
            page(
                Triple("Upgrade", box(720, 200, 820, 230), 0),
                Triple("x 3", box(700, 200, 740, 230), 0)
            )
        )
        assertNull(bar.upgradeButton())
        assertNull(bar.countLabel())
    }
}
