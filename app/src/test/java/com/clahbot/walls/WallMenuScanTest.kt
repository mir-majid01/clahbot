package com.clahbot.walls

import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controlled list scanning: the header must be detected before scrolling, and
 * overlapping viewports are merged by wall-group identity rather than by screen
 * position. A viewport that adds nothing marks the bottom (or a stalled list).
 */
class WallMenuScanTest {

    private fun line(text: String, top: Int, bottom: Int) =
        OcrLine(text, OcrBox(100, top, 400, bottom), emptyList())

    private fun candidate(
        level: Int,
        count: Int? = null,
        currency: String = "gold",
        price: Long = 1_000_000L,
        confidence: Float = 0.9f,
        id: String = "wall:L$level:C${count ?: -1}:P$price:$currency",
        title: String? = null,
        state: WallRowState = WallRowState.IDENTIFIED
    ) = WallCandidate(
        id = id,
        level = level,
        price = price,
        currency = currency,
        listIndex = 0,
        confidence = confidence,
        count = count,
        title = title,
        state = state
    )

    @Test
    fun `the buildings and heroes header enables scanning`() {
        val state = WallMenu.inspect(
            listOf(
                line("Buildings & Heroes", 100, 140),
                line("Wall x 317", 180, 210)
            ),
            1200
        )

        assertTrue(state.headerDetected)
        assertTrue(state.scanEnabled)
        assertEquals(140, state.listTop)
        assertEquals(1200, state.listBottom)
    }

    @Test
    fun `without the header the scanner refuses instead of scrolling blindly`() {
        val state = WallMenu.inspect(listOf(line("Home village", 100, 140)), 1200)

        assertFalse(state.headerDetected)
        assertFalse(state.scanEnabled)
        assertNotNull(state.reason)
        assertTrue(
            "a refusal must log the raw top-band text",
            state.headerDetail.contains("Home village")
        )
    }

    @Test
    fun `a split buildings and heroes header in the same band is accepted`() {
        val state = WallMenu.inspect(
            listOf(
                line("Buildings", 100, 130),
                line("Heroes", 140, 170),
                line("Wall x 317", 220, 250)
            ),
            1200
        )

        assertTrue(state.headerDetected)
        assertTrue(state.scanEnabled)
        assertEquals(170, state.listTop)
    }

    @Test
    fun `an ocr variant of buildings is accepted`() {
        val state = WallMenu.inspect(
            listOf(line("Bulding & Heroes", 100, 140)),
            1200
        )

        assertTrue(state.headerDetected)
    }

    @Test
    fun `a heroes heading far below the top band is not the list header`() {
        val state = WallMenu.inspect(
            listOf(
                line("Buildings", 100, 130),
                line("Heroes", 700, 740)
            ),
            1200
        )

        assertFalse(state.headerDetected)
    }

    @Test
    fun `a header below the top band is not the list header`() {
        val state = WallMenu.inspect(
            listOf(line("Buildings & Heroes", 500, 540)),
            1200
        )

        assertFalse(state.headerDetected)
    }

    @Test
    fun `an overlapping viewport does not duplicate a wall group`() {
        val existing = listOf(candidate(17, count = 317, price = 2_500_000L))
        val incoming = listOf(candidate(17, count = 317, price = 2_500_000L, confidence = 0.8f))

        val merged = WallMenu.merge(existing, incoming)

        assertEquals(1, merged.rows.size)
        assertEquals(0, merged.added)
        assertTrue(merged.atBottom)
    }

    @Test
    fun `the clearer reading wins but keeps the original list position`() {
        val existing = listOf(candidate(17, count = 317, price = 2_500_000L, confidence = 0.6f))
        val incoming = listOf(
            candidate(17, count = 317, price = 2_500_000L, confidence = 0.95f)
                .copy(listIndex = 4)
        )

        val merged = WallMenu.merge(existing, incoming)

        assertEquals(1, merged.rows.size)
        assertEquals(0, merged.rows.single().listIndex)
        assertEquals(0.95f, merged.rows.single().confidence, 0.0001f)
    }

    @Test
    fun `new rows keep list order across viewports`() {
        val existing = listOf(candidate(16, count = 12, price = 1_000_000L))
        val incoming = listOf(
            candidate(15, count = 40, price = 900_000L),
            candidate(14, count = 83, price = 750_000L)
        )

        val merged = WallMenu.merge(existing, incoming)

        assertEquals(3, merged.rows.size)
        assertEquals(2, merged.added)
        assertEquals(listOf(0, 1, 2), merged.rows.map { it.listIndex })
        assertFalse(merged.atBottom)
    }

    @Test
    fun `a missing count on one viewport still de-duplicates the same level`() {
        val existing = listOf(candidate(17, count = 317, price = 2_500_000L))
        val incoming = listOf(candidate(17, count = null, price = 2_500_000L))

        val merged = WallMenu.merge(existing, incoming)

        assertEquals(1, merged.rows.size)
        assertEquals(317, merged.rows.single().count)
    }

    @Test
    fun `unreadable rows are never merged together`() {
        val existing = listOf(candidate(0, id = "unknown:1"))
        val incoming = listOf(candidate(0, id = "unknown:2"))

        val merged = WallMenu.merge(existing, incoming)

        assertEquals(2, merged.rows.size)
        assertEquals(1, merged.added)
    }

    @Test
    fun `different currencies at the same level stay distinct`() {
        val existing = listOf(candidate(17, count = 10, currency = "gold"))
        val incoming = listOf(candidate(17, count = 10, currency = "elixir"))

        val merged = WallMenu.merge(existing, incoming)

        assertEquals(2, merged.rows.size)
    }

    @Test
    fun `a different title at the same level and price stays a distinct row`() {
        // The title is part of the row identity: two different wall groups can
        // share a level and a price, and collapsing them would hide a row.
        val existing = listOf(
            candidate(17, count = 317, price = 2_500_000L, title = "Wall x 317")
        )
        val incoming = listOf(
            candidate(17, count = 713, price = 2_500_000L, title = "Wall x 713")
        )

        val merged = WallMenu.merge(existing, incoming)

        assertEquals(2, merged.rows.size)
        assertEquals(1, merged.added)
        assertFalse(merged.atBottom)
    }

    @Test
    fun `a partial row folds into the identified reading of the same group`() {
        // The same wall seen again in an overlapping viewport without a
        // readable price: one row, not two, and the clearer reading survives.
        val existing = listOf(
            candidate(
                17, count = 317, price = 2_500_000L, confidence = 0.9f,
                title = "Wall x 317"
            )
        )
        val incoming = listOf(
            candidate(
                17, count = 317, price = 0L, confidence = 0.35f,
                currency = "unknown", title = "Wall x 317",
                state = WallRowState.PARTIAL
            )
        )

        val merged = WallMenu.merge(existing, incoming)

        assertEquals(1, merged.rows.size)
        assertEquals(0, merged.added)
        assertEquals(2_500_000L, merged.rows.single().price)
        assertEquals(WallRowState.IDENTIFIED, merged.rows.single().state)
    }

    @Test
    fun `an empty first viewport is not reported as the bottom`() {
        val merged = WallMenu.merge(emptyList(), emptyList())

        assertFalse(merged.atBottom)
        assertEquals(0, merged.added)
    }

    @Test
    fun `two rows whose counts are both unreadable stay distinct`() {
        // Both count-less: they could genuinely be two different walls at the
        // same level and price. Collapsing them caused a false "bottom reached"
        // after a single row.
        val existing = listOf(candidate(17, count = null, price = 2_500_000L))
        val incoming = listOf(candidate(17, count = null, price = 2_500_000L, confidence = 0.7f))

        val merged = WallMenu.merge(existing, incoming)

        assertEquals(2, merged.rows.size)
        assertEquals(1, merged.added)
        assertFalse(merged.atBottom)
    }

    // ---- header fallback: a visible wall title proves the list is open ------

    @Test
    fun `a visible wall title anchors the list when the header is missed`() {
        // Device recording: `Wall x 230` was plainly on screen while the panel
        // said "list not detected". The tabs were OCR'd unreadably, so the
        // header check failed even though the list was open.
        val state = WallMenu.inspect(
            listOf(
                line("Suggested upgrades", 120, 150),
                line("Wall x 230", 180, 210)
            ),
            1200
        )

        assertTrue("a wall title is proof the list is open", state.headerDetected)
        assertTrue(state.scanEnabled)
        // The anchor row starts the list; the row above it is not list content.
        assertEquals(150, state.listTop)
        assertEquals(1200, state.listBottom)
        assertTrue(
            "the fallback must say which title anchored it",
            state.headerDetail.contains("Wall x 230")
        )
    }

    @Test
    fun `a wall title far down the list still anchors it`() {
        // A scan can start with the list already scrolled: the wall rows are
        // then outside the top band the header is searched in. The title is
        // still unambiguous proof, so the whole frame is searched.
        val state = WallMenu.inspect(
            listOf(
                line("Home village", 100, 140),
                line("Wall x 254", 700, 740)
            ),
            1200
        )

        assertTrue(state.headerDetected)
        assertTrue(state.scanEnabled)
        assertEquals(660, state.listTop)
    }

    @Test
    fun `a non-wall title never anchors the list`() {
        // "Spring Trap x 6" is a row in the same list but is NOT a wall; it must
        // not be used as proof of the wall list, and it must not be reported as
        // a wall either.
        val state = WallMenu.inspect(
            listOf(
                line("Home village", 100, 140),
                line("Spring Trap x 6", 700, 740)
            ),
            1200
        )

        assertFalse(state.headerDetected)
        assertFalse(state.scanEnabled)
        assertNotNull(state.reason)
    }

    @Test
    fun `the anchored list still yields a usable signature`() {
        // The signature is what proves the list did not move; an anchored list
        // must not collapse to an empty signature.
        val frame = listOf(
            line("Suggested upgrades", 120, 150),
            line("Wall x 230", 180, 210),
            line("Wall x 12", 260, 290)
        )

        val signature = WallMenu.listSignature(frame, 1200)

        assertEquals(listOf("wallx230", "wallx12"), signature)
    }

    // ---- list signature: the only honest "the list did not move" signal -----

    private fun headerAndRows(vararg rows: Pair<String, Int>) =
        listOf(line("Buildings & Heroes", 100, 140)) +
            rows.map { (text, top) -> line(text, top, top + 40) }

    @Test
    fun `the signature covers only the list below the header`() {
        val frame = headerAndRows("Wall x 317" to 180, "Wall x 12" to 260)

        val signature = WallMenu.listSignature(frame, 1200)

        assertEquals(listOf("wallx317", "wallx12"), signature)
    }

    @Test
    fun `a moved list produces a different signature`() {
        val before = WallMenu.listSignature(
            headerAndRows("Wall x 317" to 180, "Wall x 12" to 260),
            1200
        )
        val after = WallMenu.listSignature(
            headerAndRows("Wall x 12" to 180, "Wall x 5" to 260),
            1200
        )

        assertFalse(WallMenu.framesUnchanged(before, after))
    }

    @Test
    fun `an unchanged list keeps its signature even with ocr jitter above the header`() {
        // The HUD counters above the header flicker between frames; only the
        // list content decides whether the scroll moved anything.
        val beforeLines = listOf(line("Gold 13 354 239", 20, 50)) +
            headerAndRows("Wall x 317" to 180, "Wall x 12" to 260)
        val afterLines = listOf(line("Gold 13 354 240", 20, 50)) +
            headerAndRows("Wall x 317" to 180, "Wall x 12" to 260)

        val before = WallMenu.listSignature(beforeLines, 1200)
        val after = WallMenu.listSignature(afterLines, 1200)

        assertTrue(WallMenu.framesUnchanged(before, after))
    }

    @Test
    fun `an empty signature never counts as unchanged`() {
        assertFalse(WallMenu.framesUnchanged(emptyList(), emptyList()))
    }
}
