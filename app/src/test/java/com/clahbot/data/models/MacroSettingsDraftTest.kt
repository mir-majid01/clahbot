package com.clahbot.data.models

import com.clahbot.execution.MacroTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroSettingsDraftTest {

    private fun macro(
        speed: Float = 1f,
        repeat: String = Macro.REPEAT_ONCE,
        delay: Long = 800L,
        countdown: Long = 0L,
        stopMinutes: Int? = null,
        stopAttacks: Int? = null,
        pinned: Boolean = false,
        stopOnBackground: Boolean = true
    ) = Macro(
        id = 9L,
        name = "PIN · A",
        speedMultiplier = speed,
        repeatMode = repeat,
        repeatDelayMs = delay,
        startCountdownMs = countdown,
        stopAfterMinutes = stopMinutes,
        stopAfterAttacks = stopAttacks,
        pinned = pinned,
        stopOnBackground = stopOnBackground,
        gestures = emptyList()
    )

    @Test
    fun theSheetAndTheFloatingMenuStartFromTheSameDraft() {
        val source = macro(speed = 1.2f, repeat = "5", delay = 1_400L, pinned = true)
        val draft = MacroSettingsDraft.from(source)
        assertEquals(source.name, draft.name)
        assertEquals(1.2f, draft.speed)
        assertEquals("5", draft.repeat)
        assertEquals(5, draft.repeatCount)
        assertEquals(1_400L, draft.delayMs)
        assertTrue(draft.pinned)
    }

    @Test
    fun savingTheDraftAppliesEveryFieldIncludingStopConditions() {
        val draft = MacroSettingsDraft(
            name = "Farm loop",
            speed = 0.8f,
            repeat = Macro.REPEAT_INFINITE,
            repeatCount = 12,
            delayMs = 2_500L,
            countdownSec = 7,
            stopOnBackground = false,
            stopMinutes = 45,
            stopAttacks = 30,
            pinned = true
        )
        val saved = draft.toMacro(macro())
        assertEquals("Farm loop", saved.name)
        assertEquals(0.8f, saved.speedMultiplier)
        assertEquals(Macro.REPEAT_INFINITE, saved.repeatMode)
        assertEquals(2_500L, saved.repeatDelayMs)
        assertEquals(7_000L, saved.startCountdownMs)
        assertFalse(saved.stopOnBackground)
        assertEquals(45, saved.stopAfterMinutes)
        assertEquals(30, saved.stopAfterAttacks)
        assertTrue(saved.pinned)
    }

    @Test
    fun aCustomRepeatUsesTheCountAndACustomNameIsCapped() {
        val draft = MacroSettingsDraft.from(macro(repeat = "3"))
        val saved = draft.copy(name = "x".repeat(80)).toMacro(macro(repeat = "3"))
        assertEquals(MacroTimeline.customRepeatMode(3), saved.repeatMode)
        assertEquals(40, saved.name.length)
    }

    @Test
    fun theResetValuesMatchTheFloatingMenuDefaults() {
        val reset = MacroSettingsDraft.from(
            macro(
                speed = 1f,
                repeat = Macro.REPEAT_ONCE,
                delay = Macro.LEGACY_REPEAT_DELAY_MS,
                countdown = 0L,
                stopMinutes = null,
                stopAttacks = null,
                pinned = false
            )
        )
        assertEquals(1f, reset.speed)
        assertEquals(Macro.REPEAT_ONCE, reset.repeat)
        assertEquals(Macro.LEGACY_REPEAT_DELAY_MS, reset.delayMs)
        assertEquals(0, reset.countdownSec)
        assertTrue(reset.stopOnBackground)
        assertEquals(null, reset.stopMinutes)
        assertEquals(null, reset.stopAttacks)
        assertFalse(reset.pinned)
    }
}
