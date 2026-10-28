package com.clahbot.execution

import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPlanTest {

    @Test
    fun onceLoopAndCustomSetIterationCounts() {
        assertEquals(1, PlaybackSettings.planFor(macro()).iterations)
        assertEquals(
            Int.MAX_VALUE,
            PlaybackSettings.planFor(macro(repeat = Macro.REPEAT_INFINITE)).iterations
        )
        assertEquals(7, PlaybackSettings.planFor(macro(repeat = "7")).iterations)
    }

    @Test
    fun attackLimitCapsLoopAndCustom() {
        val loop = PlaybackSettings.planFor(
            macro(repeat = Macro.REPEAT_INFINITE, stopAfterAttacks = 5)
        )
        assertEquals(5, loop.iterations)
        assertFalse(loop.infinite)

        val custom = PlaybackSettings.planFor(
            macro(repeat = "100", stopAfterAttacks = 8)
        )
        assertEquals(8, custom.iterations)

        val below = PlaybackSettings.planFor(
            macro(repeat = "3", stopAfterAttacks = 40)
        )
        assertEquals(3, below.iterations)
    }

    @Test
    fun countdownIsClampedAndCarried() {
        assertEquals(0L, PlaybackSettings.planFor(macro()).countdownMs)
        assertEquals(5_000L, PlaybackSettings.planFor(macro(countdownMs = 5_000L)).countdownMs)
        assertEquals(
            Macro.MAX_START_COUNTDOWN_MS,
            PlaybackSettings.planFor(macro(countdownMs = 99_000L)).countdownMs
        )
    }

    @Test
    fun stopAfterMinutesProducesADeadline() {
        val plan = PlaybackSettings.planFor(macro(stopAfterMinutes = 2))
        assertEquals(120_000L, plan.deadlineMs)
        assertFalse(PlaybackSettings.deadlineReached(plan, 119_999L))
        assertTrue(PlaybackSettings.deadlineReached(plan, 120_000L))
        assertNull(PlaybackSettings.planFor(macro()).deadlineMs)
    }

    @Test
    fun backgroundStopIsPassedThrough() {
        assertTrue(PlaybackSettings.planFor(macro()).stopOnBackground)
        assertFalse(
            PlaybackSettings.planFor(macro(stopOnBackground = false)).stopOnBackground
        )
    }

    private fun macro(
        repeat: String = Macro.REPEAT_ONCE,
        countdownMs: Long = 0L,
        stopAfterMinutes: Int? = null,
        stopAfterAttacks: Int? = null,
        stopOnBackground: Boolean = true
    ) = Macro(
        id = 1,
        name = "Test",
        durationMs = 100,
        repeatMode = repeat,
        startCountdownMs = countdownMs,
        stopAfterMinutes = stopAfterMinutes,
        stopAfterAttacks = stopAfterAttacks,
        stopOnBackground = stopOnBackground,
        gestures = listOf(
            RecordedGesture(
                durationMs = 50,
                points = listOf(
                    GesturePoint(0.5f, 0.5f, 0L),
                    GesturePoint(0.5f, 0.5f, 50L)
                )
            )
        )
    )
}
