package com.clahbot.data.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The totals the Statistics page and Lifetime are built from. A flagged attack
 * used to be dropped whole, which is how two attacks with visible loot could sit
 * next to a Lifetime of zero.
 */
class SessionAggregatorTest {

    private fun record(
        id: Long,
        gold: Long? = null,
        elixir: Long? = null,
        dark: Long? = null,
        bonusGold: Long? = null,
        complete: Boolean = true,
        reviewReason: String? = null,
        bonusState: String? = null,
        confirmed: Boolean = true
    ) = AttackRecord(
        id = id,
        startedAt = 1_000L * id,
        durationMs = 60_000L,
        gold = gold,
        elixir = elixir,
        darkElixir = dark,
        bonusGold = bonusGold,
        complete = complete,
        reviewReason = reviewReason,
        bonusState = bonusState,
        confirmed = confirmed
    )

    @Test
    fun `a partial read still contributes the loot it actually saw`() {
        val totals = SessionAggregator.totals(
            listOf(
                record(1, gold = 500_000L, elixir = 300_000L),
                // Main loot read, bonus panel never captured.
                record(2, gold = 20_000L, elixir = 0L, bonusState = AttackRecord.BONUS_NOT_CAPTURED)
            )
        )
        assertEquals(520_000L, totals.gold)
        assertEquals(300_000L, totals.elixir)
        assertEquals(2, totals.attackCount)
        assertEquals(1, totals.incompleteAttacks)
        assertEquals(1, totals.completeAttacks)
    }

    @Test
    fun `a review reason does not hide the loot already read`() {
        val totals = SessionAggregator.totals(
            listOf(record(1, gold = 1_200_000L, reviewReason = "Dark elixir unreadable"))
        )
        assertEquals(1_200_000L, totals.gold)
        assertEquals(1, totals.incompleteAttacks)
    }

    @Test
    fun `an unread screen adds nothing rather than inventing a zero`() {
        val totals = SessionAggregator.totals(
            listOf(record(1, complete = false, reviewReason = "The screen never settled"))
        )
        assertEquals(0L, totals.gold)
        assertEquals(0L, totals.totalGold)
        assertEquals(1, totals.attackCount)
        assertEquals(1, totals.incompleteAttacks)
    }

    @Test
    fun `recanning one attack to a higher value adds only the difference`() {
        val before = SessionAggregator.totals(listOf(record(1, gold = 500_000L)))
        val after = SessionAggregator.totals(listOf(record(1, gold = 520_000L)))
        assertEquals(500_000L, before.gold)
        assertEquals(520_000L, after.gold)
        assertEquals(1, after.attackCount)
    }

    @Test
    fun `unconfirmed sessions are not attacks at all`() {
        val totals = SessionAggregator.totals(
            listOf(record(1, gold = 900L, confirmed = false), record(2, gold = 400L))
        )
        assertEquals(1, totals.attackCount)
        assertEquals(400L, totals.gold)
    }

    @Test
    fun `bonus loot is kept separate from main loot`() {
        val totals = SessionAggregator.totals(
            listOf(record(1, gold = 1_000L, bonusGold = 250L))
        )
        assertEquals(1_000L, totals.gold)
        assertEquals(250L, totals.bonusGold)
        assertEquals(1_250L, totals.totalGold)
    }

    @Test
    fun `a negative duration cannot shrink the session`() {
        val totals = SessionAggregator.totals(listOf(record(1, gold = 1L).copy(durationMs = -5_000L)))
        assertEquals(0L, totals.durationMs)
    }
}
