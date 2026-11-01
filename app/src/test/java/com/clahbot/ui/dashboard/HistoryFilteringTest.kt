package com.clahbot.ui.dashboard

import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.GameSession
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFilteringTest {

    private val now = 1_800_000_000_000L
    private val week = HistoryFiltering.WEEK_MS

    private fun session(id: Long, startedAt: Long, attacks: List<Long>) = GameSession(
        id = id,
        startedAt = startedAt,
        lastUpdatedAt = startedAt,
        endedAt = startedAt + 60_000L,
        status = GameSession.CLOSED,
        attackIds = attacks,
        attackCount = attacks.size
    )

    private fun attack(id: Long, startedAt: Long, review: String? = null) = AttackRecord(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 30_000L,
        durationMs = 30_000L,
        confirmed = true,
        complete = review == null,
        reviewReason = review
    )

    @Test
    fun allKeepsNewestFirst() {
        val sessions = listOf(
            session(1, now - 2 * week, listOf(11)),
            session(2, now - 1_000L, listOf(21))
        )
        val attacks = listOf(attack(11, now - 2 * week), attack(21, now - 1_000L))
        val result = HistoryFiltering.sessions(sessions, attacks, HistoryFilter.ALL, now)
        assertEquals(listOf(2L, 1L), result.map { it.id })
    }

    @Test
    fun weekExcludesOlderSessions() {
        val sessions = listOf(
            session(1, now - week - 1, listOf(11)),
            session(2, now - week + 1, listOf(21))
        )
        val attacks = listOf(attack(11, now - week - 1), attack(21, now - week + 1))
        val result = HistoryFiltering.sessions(sessions, attacks, HistoryFilter.WEEK, now)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun weekKeepsALongSessionThatHasAnAttackThisWeek() {
        val oldStart = now - 2 * week
        val sessions = listOf(session(1, oldStart, listOf(11)))
        val attacks = listOf(attack(11, now - 1_000L))
        val result = HistoryFiltering.sessions(sessions, attacks, HistoryFilter.WEEK, now)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun needsReviewKeepsOnlySessionsWithReviewAttacks() {
        val sessions = listOf(
            session(1, now - 5_000L, listOf(11)),
            session(2, now - 10_000L, listOf(21))
        )
        val attacks = listOf(
            attack(11, now - 5_000L),
            attack(21, now - 10_000L, "Bonus unreadable")
        )
        val result = HistoryFiltering.sessions(sessions, attacks, HistoryFilter.REVIEW, now)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun orphansAreAttacksOutsideEverySession() {
        val sessions = listOf(session(1, now - 5_000L, listOf(11)))
        val attacks = listOf(
            attack(11, now - 5_000L),
            attack(99, now - 50_000L),
            attack(98, now - 2 * week)
        )
        val all = HistoryFiltering.orphans(sessions, attacks, HistoryFilter.ALL, now)
        assertEquals(listOf(99L, 98L), all.map { it.id })

        val weekOnly = HistoryFiltering.orphans(sessions, attacks, HistoryFilter.WEEK, now)
        assertEquals(listOf(99L), weekOnly.map { it.id })
    }

    @Test
    fun reviewOrphansUseTheSameDefinitionAsTheBanner() {
        val attacks = listOf(
            attack(1, now - 1_000L),
            attack(2, now - 2_000L, "Loot unreadable"),
            attack(3, now - 3_000L).copy(complete = false)
        )
        val result = HistoryFiltering.orphans(
            emptyList(),
            attacks,
            HistoryFilter.REVIEW,
            now
        )
        assertEquals(listOf(2L, 3L), result.map { it.id })
    }
}
