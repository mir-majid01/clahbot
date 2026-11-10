package com.clahbot.recovery

import com.clahbot.game.GameState
import com.clahbot.game.ScreenObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackGateTest {

    private fun observation(state: GameState, hash: String = "") = ScreenObservation(
        state = state,
        confidence = 0.9f,
        frameWidth = 1000,
        frameHeight = 500,
        hash = hash
    )

    private fun gateRule(
        id: String = "gate",
        state: GameState? = GameState.SEARCHING,
        action: RecoveryActionType = RecoveryActionType.WAIT,
        waitForTransition: Boolean = true,
        enabled: Boolean? = null,
        matchHash: String? = null
    ) = RecoveryRule(
        id = id,
        name = id,
        action = action,
        expectedState = state,
        waitForTransition = waitForTransition,
        enabled = enabled,
        matchHash = matchHash
    )

    @Test
    fun `a wait-for-transition rule holds on its expected screen`() {
        val holder = PlaybackGate.holdingRule(
            observation(GameState.SEARCHING),
            listOf(gateRule())
        ) { null }
        assertEquals("gate", holder?.id)
    }

    @Test
    fun `the same rule releases once the screen changes`() {
        assertNull(
            PlaybackGate.holdingRule(observation(GameState.BATTLE), listOf(gateRule())) { null }
        )
    }

    @Test
    fun `a rule that does not wait for a transition does not hold`() {
        assertNull(
            PlaybackGate.holdingRule(
                observation(GameState.SEARCHING),
                listOf(gateRule(waitForTransition = false))
            ) { null }
        )
    }

    @Test
    fun `a disabled rule does not hold`() {
        assertNull(
            PlaybackGate.holdingRule(
                observation(GameState.SEARCHING),
                listOf(gateRule(enabled = false))
            ) { null }
        )
    }

    @Test
    fun `a non-wait action does not hold`() {
        assertNull(
            PlaybackGate.holdingRule(
                observation(GameState.SEARCHING),
                listOf(gateRule(action = RecoveryActionType.GO_HOME))
            ) { null }
        )
    }

    @Test
    fun `popup and unknown screens are never gated`() {
        assertNull(PlaybackGate.holdingRule(observation(GameState.POPUP), listOf(gateRule(state = GameState.POPUP))) { null })
        assertNull(PlaybackGate.holdingRule(observation(GameState.UNKNOWN), listOf(gateRule(state = GameState.UNKNOWN))) { null })
    }

    @Test
    fun `a hash-matched gate holds within the taught distance only`() {
        val rule = gateRule(matchHash = "stored")
        assertEquals(
            "gate",
            PlaybackGate.holdingRule(observation(GameState.SEARCHING, hash = "live"), listOf(rule)) { 2 }?.id
        )
        assertNull(
            PlaybackGate.holdingRule(observation(GameState.SEARCHING, hash = "live"), listOf(rule)) { 4 }
        )
    }
}
