package com.clahbot.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStopPolicyTest {

    private fun at(
        repeat: Int,
        runs: Int,
        attacks: Int = 0,
        attackLimit: Int? = null,
        minutes: Long = 0,
        minuteLimit: Int? = null
    ) = PlaybackStopPolicy.atRunEnd(
        repeatRuns = repeat,
        runsCompleted = runs,
        attacks = attacks,
        attackLimit = attackLimit,
        runningMs = minutes * 60_000L,
        minuteLimit = minuteLimit
    )

    @Test
    fun onceEndsAfterOneRun() {
        assertEquals("Repeat finished (1 run)", at(1, 1).reason)
    }

    @Test
    fun loopWithTenAttacksEndsAtTen() {
        assertFalse(at(Int.MAX_VALUE, 9, attacks = 9, attackLimit = 10).stop)
        val decision = at(Int.MAX_VALUE, 10, attacks = 10, attackLimit = 10)
        assertTrue(decision.stop)
        assertEquals("Reached 10 attacks", decision.reason)
    }

    @Test
    fun customFiveEndsBeforeTenAttacks() {
        assertFalse(at(5, 4, attacks = 4, attackLimit = 10).stop)
        assertEquals("Repeat finished (5 runs)", at(5, 5, attacks = 5, attackLimit = 10).reason)
    }

    @Test
    fun customFiveEndsAtFiveAttacksWhenThatComesFirst() {
        assertEquals("Reached 5 attacks", at(10, 5, attacks = 5, attackLimit = 5).reason)
    }

    @Test
    fun minutesEndTheRunAtItsEnd() {
        assertFalse(at(Int.MAX_VALUE, 1, minutes = 29, minuteLimit = 30).stop)
        assertEquals("Reached 30 min", at(Int.MAX_VALUE, 1, minutes = 30, minuteLimit = 30).reason)
    }

    @Test
    fun theEarliestOfTimeAndAttacksWins() {
        assertEquals(
            "Reached 30 min",
            at(Int.MAX_VALUE, 1, attacks = 10, attackLimit = 10, minutes = 30, minuteLimit = 30).reason
        )
    }

    @Test
    fun noLimitsAndLoopNeverStop() {
        assertFalse(at(Int.MAX_VALUE, 100, minutes = 100).stop)
    }

    @Test
    fun onceWithTenAttackLimitStillEndsAtOnce() {
        assertEquals("Repeat finished (1 run)", at(1, 1, attacks = 1, attackLimit = 10).reason)
    }

    @Test
    fun threeAttacksStopACustomTenBeforeItsPlan() {
        assertEquals("Reached 3 attacks", at(10, 3, attacks = 3, attackLimit = 3).reason)
    }
}
