package com.clahbot.walls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part 2's display-only decision. These tests pin the exact wording the panel
 * shows and, more importantly, that every unknown is a block: a dry run that
 * says "would upgrade" for a village the runtime would refuse would be a lie.
 *
 * There is no device, no gesture and no repository here — the function only
 * reads a finished report and the user's reserves.
 */
class AutoWallsDryRunTest {

    private fun wall(
        level: Int = 17,
        price: Long = 2_500_000L,
        currency: String = "gold",
        count: Int? = 317
    ) = WallCandidate(
        id = "row:0",
        level = level,
        price = price,
        currency = currency,
        listIndex = 0,
        confidence = 0.9f,
        count = count,
        title = count?.let { "Wall x $it" },
        state = WallRowState.IDENTIFIED
    )

    private fun report(
        selected: WallCandidate? = wall(),
        captured: Boolean = true,
        reason: String? = null,
        gold: Long? = 10_000_000L,
        elixir: Long? = 10_000_000L,
        goldStale: Boolean = false,
        elixirStale: Boolean = false,
        free: Int? = 1,
        total: Int? = 6
    ) = AutoWallsScanReport(
        at = 1L,
        captured = captured,
        reason = reason,
        selected = selected,
        goldBalance = gold,
        elixirBalance = elixir,
        goldStale = goldStale,
        elixirStale = elixirStale,
        builderFree = free,
        builderTotal = total
    )

    @Test
    fun `a payable wall with a free builder would be upgraded`() {
        val decision = AutoWallsDryRun.decide(report(), WallTriggerConfig())

        assertTrue(decision.text, decision.wouldUpgrade)
        assertEquals(
            "would upgrade Wall x 317 (level 17, 2,500,000 gold)",
            decision.text
        )
    }

    @Test
    fun `a busy builder strip blocks even when the balance is enough`() {
        val decision = AutoWallsDryRun.decide(
            report(free = 0, total = 6),
            WallTriggerConfig()
        )

        assertFalse(decision.wouldUpgrade)
        assertEquals("blocked: no free regular builder (0/6 free)", decision.text)
    }

    @Test
    fun `an unreadable builder strip blocks instead of assuming a builder`() {
        val decision = AutoWallsDryRun.decide(report(free = null), WallTriggerConfig())

        assertEquals("blocked: builder availability unknown", decision.text)
    }

    @Test
    fun `spending into the reserve is blocked with the arithmetic shown`() {
        val decision = AutoWallsDryRun.decide(
            report(gold = 3_000_000L),
            WallTriggerConfig(goldReserve = 1_000_000L)
        )

        assertFalse(decision.wouldUpgrade)
        assertEquals(
            "blocked: gold reserve would be broken: 3,000,000 - 2,500,000 = 500,000 < 1,000,000",
            decision.text
        )
    }

    @Test
    fun `an elixir wall uses the elixir balance and reserve`() {
        val decision = AutoWallsDryRun.decide(
            report(
                selected = wall(price = 750_000L, currency = "elixir"),
                gold = 0L,
                elixir = 2_000_000L
            ),
            WallTriggerConfig(elixirReserve = 1_000_000L)
        )

        assertTrue(decision.text, decision.wouldUpgrade)
        assertEquals(
            "would upgrade Wall x 317 (level 17, 750,000 elixir)",
            decision.text
        )
    }

    @Test
    fun `an unreadable balance blocks`() {
        val decision = AutoWallsDryRun.decide(report(gold = null), WallTriggerConfig())

        assertEquals("blocked: gold balance not readable", decision.text)
    }

    @Test
    fun `a stale balance blocks because it is not live`() {
        val decision = AutoWallsDryRun.decide(report(goldStale = true), WallTriggerConfig())

        assertEquals(
            "blocked: gold balance is stale (not read on this frame)",
            decision.text
        )
    }

    @Test
    fun `no selected wall carries the scan's own reason`() {
        val decision = AutoWallsDryRun.decide(
            report(selected = null, reason = "no wall rows found on this frame"),
            WallTriggerConfig()
        )

        assertEquals("blocked: no wall rows found on this frame", decision.text)
    }

    @Test
    fun `an uncaptured frame is blocked`() {
        val decision = AutoWallsDryRun.decide(
            report(captured = false, reason = "Clash of Clans must be on screen"),
            WallTriggerConfig()
        )

        assertEquals("blocked: Clash of Clans must be on screen", decision.text)
    }

    @Test
    fun `a dark-elixir row is never treated as payable`() {
        val decision = AutoWallsDryRun.decide(
            report(selected = wall(currency = "dark")),
            WallTriggerConfig()
        )

        assertEquals("blocked: cannot pay with dark", decision.text)
    }

    @Test
    fun `an unreadable wall count still names the row honestly`() {
        val decision = AutoWallsDryRun.decide(
            report(selected = wall(count = null)),
            WallTriggerConfig()
        )

        assertEquals(
            "would upgrade Wall (count unreadable) (level 17, 2,500,000 gold)",
            decision.text
        )
    }

    // ---- reserve fields ----------------------------------------------------

    @Test
    fun `typed reserves keep only the digits the game shows as separators`() {
        assertEquals(1_000_000L, AutoWallsDryRun.parseAmount("1 000 000"))
        assertEquals(2_500_000L, AutoWallsDryRun.parseAmount("2,500,000"))
        assertEquals(7L, AutoWallsDryRun.parseAmount("7"))
        assertNull(AutoWallsDryRun.parseAmount(""))
        assertNull(AutoWallsDryRun.parseAmount("   "))
    }

    @Test
    fun `a zero reserve shows an empty field instead of a zero`() {
        assertEquals("", AutoWallsDryRun.formatAmount(0L))
        assertEquals("1000000", AutoWallsDryRun.formatAmount(1_000_000L))
    }
}
