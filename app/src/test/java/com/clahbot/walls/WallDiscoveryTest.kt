package com.clahbot.walls

import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dynamic wall discovery over OCR lines (Auto Walls 2.0 PRD §3.2/§3.3). These
 * tests model the Buildings & Heroes list as stacked text lines and prove the
 * pipeline is coordinate-independent (positions change, still detected) and
 * refuses to guess when a level/price/currency is missing.
 */
class WallDiscoveryTest {

    private var currencyByTop: Map<IntRange, String> = emptyMap()

    private fun line(text: String, top: Int, bottom: Int, left: Int = 20, right: Int = 220) =
        OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    private fun currencyAt(box: OcrBox): String? =
        currencyByTop.entries.firstOrNull { box.top in it.key }?.value

    @Test
    fun `a wall row yields a candidate with level price and currency`() {
        currencyByTop = mapOf((160..190) to "gold")
        val lines = listOf(
            line("Wall", 100, 130),
            line("8", 135, 158),
            line("50,000", 162, 188),
            line("Cannon", 320, 350)
        )
        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }
        assertEquals(1, found.size)
        val wall = found.single()
        assertEquals(8, wall.level)
        assertEquals(50_000L, wall.price)
        assertEquals("gold", wall.currency)
        assertTrue("complete row must be confident", wall.confidence >= 0.6f)
    }

    @Test
    fun `non-wall rows are ignored`() {
        val lines = listOf(
            line("Cannon", 100, 130),
            line("Archer Queen", 320, 350),
            line("Bomb", 500, 530)
        )
        assertTrue(WallDiscovery.discover(lines, 600) { null }.isEmpty())
    }

    @Test
    fun `two wall rows at different positions both yield candidates with list order`() {
        currencyByTop = mapOf((160..190) to "gold", (560..590) to "elixir")
        val lines = listOf(
            line("Wall", 100, 130),
            line("8", 135, 158),
            line("50,000", 162, 188),
            line("Wall", 500, 530),
            line("9", 535, 558),
            line("80,000", 562, 588)
        )
        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }
        assertEquals(2, found.size)
        assertEquals(listOf(8, 9), found.map { it.level })
        assertEquals(0, found[0].listIndex)
        assertEquals(1, found[1].listIndex)
        assertEquals(listOf("gold", "elixir"), found.map { it.currency })
    }

    @Test
    fun `a wall row missing its price is low confidence and never selected`() {
        val lines = listOf(
            line("Wall", 100, 130),
            line("8", 135, 158)
        )
        val found = WallDiscovery.discover(lines, 600) { "gold" }
        assertEquals(1, found.size)
        assertTrue(found.single().confidence < 0.6f)
        assertNull(WallSelection.select(found, WallSelectionConfig()))
    }

    @Test
    fun `discovery follows the wall after the list scrolls`() {
        currencyByTop = mapOf((310..340) to "gold")
        // Same wall, now 200 px lower after a scroll; no fixed coordinate assumed.
        val scrolled = listOf(
            line("Wall", 300, 330),
            line("8", 332, 356),
            line("50,000", 358, 384)
        )
        val found = WallDiscovery.discover(scrolled, 600) { currencyAt(it) }
        assertEquals(1, found.size)
        assertEquals(8, found.single().level)
        assertEquals(50_000L, found.single().price)
    }

    @Test
    fun `multiple wall levels are discovered with the badge level not the wall count`() {
        // Real layout: each wall-level group is "Wall x <count>" with a badge level
        // number, priced in elixir. The count (12/83/230) must NOT be read as level.
        currencyByTop = mapOf((122..148) to "elixir", (228..254) to "elixir", (334..360) to "elixir")
        val lines = listOf(
            line("16", 60, 84),
            line("Wall x 12", 90, 118),
            line("1 000 000", 122, 148),
            line("14", 166, 190),
            line("Wall x 83", 196, 224),
            line("750 000", 228, 254),
            line("13", 272, 296),
            line("Wall x 230", 302, 330),
            line("500 000", 334, 360)
        )
        val found = WallDiscovery.discover(lines, 612) { currencyAt(it) }
        assertEquals(3, found.size)
        assertEquals(listOf(16, 14, 13), found.map { it.level })
        assertEquals(listOf(1_000_000L, 750_000L, 500_000L), found.map { it.price })
        assertTrue(found.all { it.currency == "elixir" && it.confidence >= 0.6f })
    }

    @Test
    fun `gold wall row is discovered and non-wall trap rows are ignored`() {
        currencyByTop = mapOf((122..148) to "gold")
        val lines = listOf(
            line("17", 60, 84),
            line("Wall x 317", 90, 118),
            line("2 500 000", 122, 148),
            line("Spring Trap x 6", 200, 228),
            line("500 000", 232, 258)
        )
        val found = WallDiscovery.discover(lines, 612) { currencyAt(it) }
        assertEquals(1, found.size)
        val wall = found.single()
        assertEquals(17, wall.level)
        assertEquals(2_500_000L, wall.price)
        assertEquals("gold", wall.currency)
    }

    @Test
    fun `the wall count is read from the x label for later verification`() {
        currencyByTop = mapOf((122..148) to "gold")
        val lines = listOf(
            line("17", 60, 84),
            line("Wall x 317", 90, 118),
            line("2 500 000", 122, 148)
        )
        val found = WallDiscovery.discover(lines, 612) { currencyAt(it) }
        assertEquals(317, found.single().count)
    }

    @Test
    fun `a wall label without a count leaves the count unreadable`() {
        currencyByTop = mapOf((160..190) to "gold")
        val lines = listOf(
            line("Wall", 100, 130),
            line("8", 135, 158),
            line("50,000", 162, 188)
        )
        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }
        assertNull(found.single().count)
    }

    @Test
    fun `discovery feeds the configured selection strategy`() {
        currencyByTop = mapOf((160..190) to "gold", (560..590) to "gold")
        val lines = listOf(
            line("Wall", 100, 130),
            line(" 9", 135, 158),
            line("80,000", 162, 188),
            line("Wall", 500, 530),
            line("7", 535, 558),
            line("20,000", 562, 588)
        )
        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }
        assertEquals(
            7,
            WallSelection.select(found, WallSelectionConfig(WallSelectionStrategy.CHEAPEST_FIRST))?.level
        )
        assertEquals(
            9,
            WallSelection.select(found, WallSelectionConfig(WallSelectionStrategy.HIGHEST_LEVEL_FIRST))?.level
        )
    }

    // ---- fresh tap-box relocation (runtime stage 3) ------------------------

    @Test
    fun `tapBox returns the fresh wall label box for the selected candidate`() {
        currencyByTop = mapOf((160..190) to "gold")
        val lines = listOf(
            line("Wall", 100, 130),
            line("8", 135, 158),
            line("50,000", 162, 188),
            line("Cannon", 320, 350)
        )
        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }
        val selected = WallSelection.select(found, WallSelectionConfig())!!
        val box = WallDiscovery.tapBox(selected, lines, 600) { currencyAt(it) }
        assertEquals(OcrBox(20, 100, 220, 130), box)
    }

    @Test
    fun `tapBox follows the wall to its new box after a scroll`() {
        currencyByTop = mapOf((160..190) to "gold")
        val before = listOf(
            line("Wall", 100, 130),
            line("8", 135, 158),
            line("50,000", 162, 188)
        )
        val candidate = WallDiscovery.discover(before, 600) { currencyAt(it) }.single()
        // Same wall, now 200 px lower; the price icon moved with it.
        currencyByTop = mapOf((358..384) to "gold")
        val scrolled = listOf(
            line("Wall", 300, 330),
            line("8", 332, 356),
            line("50,000", 358, 384)
        )
        val box = WallDiscovery.tapBox(candidate, scrolled, 600) { currencyAt(it) }
        assertEquals(OcrBox(20, 300, 220, 330), box)
    }

    @Test
    fun `tapBox refuses when the row no longer matches the candidate identity`() {
        currencyByTop = mapOf((160..190) to "gold")
        val lines = listOf(
            line("Wall", 100, 130),
            line("8", 135, 158),
            line("50,000", 162, 188)
        )
        val candidate = WallDiscovery.discover(lines, 600) { currencyAt(it) }.single()
        // Same row index, but a different wall now sits there.
        val changed = listOf(
            line("Wall", 100, 130),
            line("9", 135, 158),
            line("80,000", 162, 188)
        )
        assertNull(WallDiscovery.tapBox(candidate, changed, 600) { currencyAt(it) })
    }

    @Test
    fun `tapBox refuses a row that is not a wall`() {
        currencyByTop = mapOf((160..190) to "gold")
        val lines = listOf(
            line("Wall", 100, 130),
            line("8", 135, 158),
            line("50,000", 162, 188)
        )
        val candidate = WallDiscovery.discover(lines, 600) { currencyAt(it) }.single()
        val nonWall = listOf(
            line("Cannon", 100, 130),
            line("8", 135, 158),
            line("50,000", 162, 188)
        )
        assertNull(WallDiscovery.tapBox(candidate, nonWall, 600) { currencyAt(it) })
    }

    @Test
    fun `tapBox refuses an index outside the current list`() {
        val candidate = WallCandidate(
            id = "row:9", level = 8, price = 50_000L, currency = "gold",
            listIndex = 9, confidence = 0.9f
        )
        val lines = listOf(
            line("Wall", 100, 130),
            line("8", 135, 158),
            line("50,000", 162, 188)
        )
        assertNull(WallDiscovery.tapBox(candidate, lines, 600) { "gold" })
    }
}
