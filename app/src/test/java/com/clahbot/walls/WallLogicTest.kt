package com.clahbot.walls

import com.clahbot.game.GameState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallLogicTest {

    @Test
    fun `popups, unknown screens and other apps stop the run`() {
        listOf(
            GameState.POPUP,
            GameState.UNKNOWN,
            GameState.NOT_CLASH,
            GameState.BATTLE
        ).forEach { assertTrue(it.name, WallLogic.unsafeDuringRun(it)) }
        listOf(
            GameState.HOME,
            GameState.RESULTS,
            GameState.LOADING,
            GameState.ATTACK_MENU,
            null
        ).forEach { assertFalse("${it}", WallLogic.unsafeDuringRun(it)) }
    }

    @Test
    fun `a second upgrade in the same visit needs permission and proof`() {
        assertTrue(
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = true, verified = true, repeats = 0, maxRepeats = 5
            )
        )
        assertFalse(
            "the switch is off",
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = false, verified = true, repeats = 0, maxRepeats = 5
            )
        )
        assertFalse(
            "no balance drop proved the first one",
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = true, verified = false, repeats = 0, maxRepeats = 5
            )
        )
        assertFalse(
            "the last run did not spend",
            WallLogic.mayRepeatUpgrade(
                status = "stopped", allowed = true, verified = true, repeats = 0, maxRepeats = 5
            )
        )
    }

    @Test
    fun `repeats stop at the ceiling`() {
        assertTrue(
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = true, verified = true, repeats = 4, maxRepeats = 5
            )
        )
        assertFalse(
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = true, verified = true, repeats = 5, maxRepeats = 5
            )
        )
    }
}
