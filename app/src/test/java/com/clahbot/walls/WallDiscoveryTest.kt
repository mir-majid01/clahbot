package com.clahbot.walls

import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dynamic wall discovery over OCR lines (Auto Walls 2.0 PRD §3.2/§3.3).
 *
 * The geometry mirrors the real Buildings & Heroes list: one visual row holds
 * the level badge on the left, the `Wall x N` label in the middle and the price
 * on the right. Adjacent rows are separated by a field of other buildings, and
 * those buildings have their own prices. The tests prove the scanner attaches
 * only same-row lines, never a neighbour's price, refuses ambiguous rows, and
 * only treats a real `Wall x N` title as a wall.
 */
class WallDiscoveryTest {

    private var currencyByTop: Map<IntRange, String> = emptyMap()

    private fun line(
        text: String,
        top: Int,
        bottom: Int,
        left: Int = 100,
        right: Int = 260
    ) = OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    private fun currencyAt(box: OcrBox): String? =
        currencyByTop.entries.firstOrNull { box.top in it.key }?.value

    /** One list row at [top]: level badge, label and price all on the same line. */
    private fun wallRow(
        top: Int,
        level: String,
        label: String,
        price: String,
        currency: String
    ): List<OcrLine> {
        currencyByTop = currencyByTop + ((top..top + 30) to currency)
        return listOf(
            line(level, top, top + 30, 20, 60),
            line(label, top, top + 30),
            line(price, top, top + 30, 560, 760)
        )
    }

    // ---- identity ----------------------------------------------------------

    @Test
    fun `a wall row yields an identified candidate with level price and currency`() {
        val lines = wallRow(100, "8", "Wall x 317", "50,000", "gold") +
            listOf(line("Cannon", 300, 330))

        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }

        assertEquals(1, found.size)
        val wall = found.single()
        assertEquals(8, wall.level)
        assertEquals(50_000L, wall.price)
        assertEquals("gold", wall.currency)
        assertEquals(317, wall.count)
        assertEquals("Wall x 317", wall.title)
        assertEquals(WallRowState.IDENTIFIED, wall.state)
        assertTrue("complete row must be confident", wall.confidence >= 0.6f)
    }

    @Test
    fun `spring trap rows are not walls even with the same shape`() {
        val lines = wallRow(50, "17", "Wall x 317", "2 500 000", "gold") +
            wallRow(200, "9", "Spring Trap", "1 500 000", "gold") +
            wallRow(400, "8", "Spring Trap x 4", "1 000 000", "gold")

        val found = WallDiscovery.discover(lines, 612) { currencyAt(it) }

        assertEquals(1, found.size)
        assertEquals(17, found.single().level)
        assertEquals(2_500_000L, found.single().price)
    }

    @Test
    fun `wall-like names are excluded explicitly`() {
        val lines = listOf(
            wallRow(50, "5", "Wall Ring", "1 000 000", "elixir"),
            wallRow(150, "5", "Wallbreaker", "1 000 000", "elixir"),
            wallRow(250, "5", "Wall Wrecker", "1 000 000", "elixir"),
            wallRow(350, "5", "Walls", "1 000 000", "elixir"),
            wallRow(450, "5", "Wall x 5", "1 000 000", "elixir")
        ).flatten()

        val found = WallDiscovery.discover(lines, 700) { currencyAt(it) }

        assertEquals(1, found.size)
        assertEquals("Wall x 5", found.single().title)
    }

    @Test
    fun `ocr confusions in the word wall still identify a wall`() {
        val lines = listOf(
            line("4", 50, 80, 20, 60),
            line("Wal1 x 12", 50, 80),
            line("1 000 000", 50, 80, 560, 760)
        )
        currencyByTop = mapOf((50..80) to "elixir")

        val found = WallDiscovery.discover(lines, 612) { currencyAt(it) }

        assertEquals(1, found.size)
        assertEquals(12, found.single().count)
        assertEquals(4, found.single().level)
    }

    @Test
    fun `non-wall rows are ignored`() {
        val lines = listOf(
            line("Cannon", 100, 130),
            line("Archer Queen", 300, 330),
            line("Bomb", 500, 530)
        )
        assertTrue(WallDiscovery.discover(lines, 600) { null }.isEmpty())
    }

    @Test
    fun `a bare wall label is no longer a wall row`() {
        // OCR often drops the "x N" group; a title without the count is not
        // enough identity to select or tap a row.
        val lines = listOf(
            line("8", 100, 130, 20, 60),
            line("Wall", 100, 130),
            line("50,000", 100, 130, 560, 760)
        )
        currencyByTop = mapOf((100..130) to "gold")

        assertTrue(WallDiscovery.discover(lines, 600) { currencyAt(it) }.isEmpty())
    }

    // ---- list order --------------------------------------------------------

    @Test
    fun `two wall rows at different positions both yield candidates with list order`() {
        val lines = wallRow(100, "8", "Wall x 5", "50,000", "gold") +
            wallRow(300, "9", "Wall x 6", "80,000", "elixir")

        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }

        assertEquals(2, found.size)
        assertEquals(listOf(8, 9), found.map { it.level })
        assertEquals(0, found[0].listIndex)
        assertEquals(1, found[1].listIndex)
        assertEquals(listOf("gold", "elixir"), found.map { it.currency })
    }

    @Test
    fun `multiple wall levels are discovered with the badge level not the wall count`() {
        val lines = wallRow(50, "16", "Wall x 12", "1 000 000", "elixir") +
            wallRow(190, "14", "Wall x 83", "750 000", "elixir") +
            wallRow(330, "13", "Wall x 230", "500 000", "elixir")

        val found = WallDiscovery.discover(lines, 612) { currencyAt(it) }

        assertEquals(3, found.size)
        assertEquals(listOf(16, 14, 13), found.map { it.level })
        assertEquals(listOf(1_000_000L, 750_000L, 500_000L), found.map { it.price })
        assertTrue(found.all { it.currency == "elixir" && it.state == WallRowState.IDENTIFIED })
    }

    @Test
    fun `discovery follows the wall after the list scrolls`() {
        val scrolled = wallRow(300, "8", "Wall x 12", "50,000", "gold")

        val found = WallDiscovery.discover(scrolled, 600) { currencyAt(it) }

        assertEquals(1, found.size)
        assertEquals(8, found.single().level)
        assertEquals(50_000L, found.single().price)
    }

    // ---- row boundaries ----------------------------------------------------

    @Test
    fun `a neighbouring building price is never attached to the wall row`() {
        // The building row above has a 16,000,000 price; the wall row has no
        // readable price of its own. The old fixed-gap grouping merged them and
        // reported the building's price as the wall price.
        val lines = listOf(
            line("16", 40, 70, 20, 60),
            line("Cannon", 40, 70),
            line("16 000 000", 40, 70, 560, 760),
            line("17", 140, 170, 20, 60),
            line("Wall x 317", 140, 170)
        )
        currencyByTop = mapOf((40..70) to "gold")

        val found = WallDiscovery.discover(lines, 612) { currencyAt(it) }

        assertEquals(1, found.size)
        val wall = found.single()
        assertEquals(17, wall.level)
        assertEquals(0L, wall.price)
        assertEquals(WallRowState.PARTIAL, wall.state)
        assertTrue("unreadable price must stay uncertain", wall.confidence < 0.6f)
    }

    @Test
    fun `two competing prices in one row are ambiguous and never guessed`() {
        val lines = listOf(
            line("17", 100, 130, 20, 60),
            line("Wall x 317", 100, 130),
            line("2 500 000", 100, 130, 560, 760),
            line("25 000 000", 100, 130, 800, 980)
        )
        currencyByTop = mapOf((100..130) to "gold")

        val found = WallDiscovery.discover(lines, 612) { currencyAt(it) }

        val wall = found.single()
        assertEquals(17, wall.level)
        assertEquals(0L, wall.price)
        assertEquals(WallRowState.UNCERTAIN, wall.state)
        assertEquals(0.1f, wall.confidence, 0.0001f)
        assertNull(WallSelection.select(found, WallSelectionConfig()))
    }

    @Test
    fun `a wall row missing its price is partial and never selected`() {
        val lines = listOf(
            line("8", 100, 130, 20, 60),
            line("Wall x 12", 100, 130)
        )

        val found = WallDiscovery.discover(lines, 600) { "gold" }

        assertEquals(1, found.size)
        assertEquals(WallRowState.PARTIAL, found.single().state)
        assertTrue(found.single().confidence < 0.6f)
        assertNull(WallSelection.select(found, WallSelectionConfig()))
    }

    // ---- selection ---------------------------------------------------------

    @Test
    fun `the wall count is read from the x label for later verification`() {
        val lines = wallRow(50, "17", "Wall x 317", "2 500 000", "gold")

        val found = WallDiscovery.discover(lines, 612) { currencyAt(it) }

        assertEquals(317, found.single().count)
    }

    @Test
    fun `discovery feeds the configured selection strategy`() {
        val lines = wallRow(100, "9", "Wall x 4", "80,000", "gold") +
            wallRow(400, "7", "Wall x 5", "20,000", "gold")

        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }

        assertEquals(
            7,
            WallSelection.select(
                found,
                WallSelectionConfig(WallSelectionStrategy.CHEAPEST_FIRST)
            )?.level
        )
        assertEquals(
            9,
            WallSelection.select(
                found,
                WallSelectionConfig(WallSelectionStrategy.HIGHEST_LEVEL_FIRST)
            )?.level
        )
    }

    // ---- fresh tap-box relocation (runtime stage 3) ------------------------

    @Test
    fun `tapBox returns the fresh wall label box for the selected candidate`() {
        val lines = wallRow(100, "8", "Wall x 12", "50,000", "gold") +
            listOf(line("Cannon", 300, 330))
        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }
        val selected = WallSelection.select(found, WallSelectionConfig())!!

        val box = WallDiscovery.tapBox(selected, lines, 600) { currencyAt(it) }

        assertEquals(OcrBox(100, 100, 260, 130), box)
    }

    @Test
    fun `tapBox follows the wall to its new box after a scroll`() {
        val before = wallRow(100, "8", "Wall x 12", "50,000", "gold")
        val candidate = WallDiscovery.discover(before, 600) { currencyAt(it) }.single()
        // Same wall, now 200 px lower; the price icon moved with it.
        val scrolled = wallRow(300, "8", "Wall x 12", "50,000", "gold")

        val box = WallDiscovery.tapBox(candidate, scrolled, 600) { currencyAt(it) }

        assertEquals(OcrBox(100, 300, 260, 330), box)
    }

    @Test
    fun `tapBox refuses when the row no longer matches the candidate identity`() {
        val lines = wallRow(100, "8", "Wall x 12", "50,000", "gold")
        val candidate = WallDiscovery.discover(lines, 600) { currencyAt(it) }.single()
        // Same row index, but a different wall now sits there.
        val changed = wallRow(100, "9", "Wall x 12", "80,000", "gold")

        assertNull(WallDiscovery.tapBox(candidate, changed, 600) { currencyAt(it) })
    }

    @Test
    fun `tapBox refuses a row that is not a wall`() {
        val lines = wallRow(100, "8", "Wall x 12", "50,000", "gold")
        val candidate = WallDiscovery.discover(lines, 600) { currencyAt(it) }.single()
        val nonWall = listOf(
            line("Cannon", 100, 130),
            line("8", 100, 130, 20, 60),
            line("50,000", 100, 130, 560, 760)
        )

        assertNull(WallDiscovery.tapBox(candidate, nonWall, 600) { currencyAt(it) })
    }

    @Test
    fun `tapBox refuses an index outside the current list`() {
        val candidate = WallCandidate(
            id = "row:9", level = 8, price = 50_000L, currency = "gold",
            listIndex = 9, confidence = 0.9f
        )
        val lines = wallRow(100, "8", "Wall x 12", "50,000", "gold")

        assertNull(WallDiscovery.tapBox(candidate, lines, 600) { "gold" })
    }

    // ---- the round-2 defects: partial rows and the fields that failed ------

    @Test
    fun `a row with an unreadable level is reported as a gap that needs the level`() {
        // Device recording: the row read fine but the level badge was missed, so
        // it sat at PARTIAL and never reached IDENTIFIED. The gap tells the device
        // exactly which field to re-read from the bitmap.
        val lines = listOf(
            line("Wall x 230", 100, 130),
            line("500,000", 100, 130, 560, 760)
        )
        currencyByTop = mapOf((100..130) to "elixir")

        val gaps = WallDiscovery.incompleteRows(lines, 600) { currencyAt(it) }

        assertEquals(1, gaps.size)
        val gap = gaps.single()
        assertEquals("Wall x 230", gap.title)
        assertTrue(gap.needLevel)
        assertTrue(!gap.needPrice)
        assertTrue(!gap.needCurrency)
        assertEquals(100, gap.titleBox.top)
    }

    @Test
    fun `a row with no readable price needs the price field`() {
        val lines = wallRow(100, "12", "Wall x 254", "2,000,000", "gold")
            .filter { !it.text.contains("2,000,000") }

        val gaps = WallDiscovery.incompleteRows(lines, 600) { currencyAt(it) }

        assertEquals(1, gaps.size)
        assertTrue(gaps.single().needPrice)
        assertTrue(!gaps.single().needLevel)
    }

    @Test
    fun `a complete row is not a gap`() {
        val lines = wallRow(100, "12", "Wall x 12", "1,000,000", "elixir")

        val gaps = WallDiscovery.incompleteRows(lines, 600) { currencyAt(it) }

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `the level badge region sits left of the title and vertically centred on it`() {
        val title = OcrBox(200, 300, 500, 340)

        val region = WallDiscovery.levelBadgeRegion(title)

        assertTrue("badge must be left of the title", region.right <= title.left)
        assertTrue("badge must be near the title", region.right >= title.left - 20)
        assertTrue("badge must be centred on the row", title.centerY in region.top..region.bottom)
        assertTrue("badge must be tall enough to hold a digit", region.height >= 20)
    }

    @Test
    fun `the price region sits right of the title and is wide enough for a group separator`() {
        val title = OcrBox(200, 300, 500, 340)

        val region = WallDiscovery.priceRegion(title)

        assertTrue("price must be right of the title", region.left >= title.right)
        assertTrue("price region must be wide", region.width >= 100)
        assertTrue("price region must be centred on the row", title.centerY in region.top..region.bottom)
    }

    @Test
    fun `the builder strip region covers the bottom right corner only`() {
        val strip = WallDiscovery.builderStripRegion(1360, 612)

        assertEquals((1360 * 0.70f).toInt(), strip.left)
        assertEquals((612 * 0.68f).toInt(), strip.top)
        assertEquals(1360, strip.right)
        assertEquals(612, strip.bottom)
    }

    @Test
    fun `a spring trap row is never a wall even when it looks like one`() {
        // Round-2 frame: "Spring Trap x 6" with level 6 and a 500,000 price.
        val lines = listOf(
            line("6", 100, 130, 20, 60),
            line("Spring Trap x 6", 100, 130),
            line("500,000", 100, 130, 560, 760)
        )
        currencyByTop = mapOf((100..130) to "gold")

        assertTrue(WallDiscovery.discover(lines, 600) { currencyAt(it) }.isEmpty())
    }

    @Test
    fun `a real wall x 254 row with gold is identified`() {
        // Round-2 frame: the list was refused as "not detected" while this row
        // was plainly on screen with a gold price.
        val lines = listOf(
            line("16", 100, 130, 20, 60),
            line("Wall x 254", 100, 130),
            line("2,000,000", 100, 130, 560, 760)
        )
        currencyByTop = mapOf((100..130) to "gold")

        val found = WallDiscovery.discover(lines, 600) { currencyAt(it) }

        assertEquals(1, found.size)
        assertEquals(WallRowState.IDENTIFIED, found.single().state)
        assertEquals(16, found.single().level)
        assertEquals(254, found.single().count)
    }
}
