package com.clahbot.recovery

import com.clahbot.game.GameState
import com.clahbot.game.PopupKind
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rule is no longer popup-only. A rule that names its expected state can match
 * an ordinary screen such as SEARCHING and wait there, while legacy rules keep
 * the old popup-only contract.
 */
class RecoveryRuleScopeTest {

    private fun observation(
        state: GameState,
        hash: String = "",
        popupKind: PopupKind? = null,
        forbidden: List<ScreenBox> = emptyList()
    ) = ScreenObservation(
        state = state,
        confidence = 0.9f,
        popupKind = popupKind,
        forbidden = forbidden,
        frameWidth = 1000,
        frameHeight = 500,
        hash = hash
    )

    private fun searchingRule(): RecoveryRule =
        BuiltInRecoveryRules.all.first { it.id == "searching_wait" }

    // ---------- the searching-for-opponents wait rule ----------

    @Test
    fun `the searching wait rule matches a searching screen and never taps`() {
        val rule = searchingRule()
        val searching = observation(GameState.SEARCHING)
        assertTrue(
            "the searching rule must match the SEARCHING screen",
            rule.matches(searching, RuleScope.ATTACK) { null }
        )
        assertTrue("the searching rule must wait for a transition", rule.waitForTransition)
        val action = SafeActionFactory.resolve(searching, rule).action
        assertEquals(RecoveryActionType.WAIT, action?.type)
        assertNull("a WAIT action must never have a tap target", action?.target)
    }

    @Test
    fun `the searching wait rule stops matching once the enemy village is recognised`() {
        assertFalse(
            "the rule must not linger once the screen leaves SEARCHING",
            searchingRule().matches(observation(GameState.BATTLE), RuleScope.ATTACK) { null }
        )
    }

    @Test
    fun `a searching wait rule is scoped to attack runs, not wall runs`() {
        val rule = searchingRule()
        assertFalse(
            "an ATTACK rule must not fire during a wall run",
            rule.matches(observation(GameState.SEARCHING), RuleScope.WALL) { null }
        )
        assertTrue(
            "an unscoped caller may still use an ATTACK rule",
            rule.matches(observation(GameState.SEARCHING), RuleScope.BOTH) { null }
        )
    }

    @Test
    fun `a disabled rule never matches`() {
        val rule = searchingRule().copy(enabled = false)
        assertFalse(rule.matches(observation(GameState.SEARCHING), RuleScope.ATTACK) { null })
    }

    @Test
    fun `legacy popup rules still refuse non-popup screens`() {
        val generic = BuiltInRecoveryRules.all.first { it.id == "generic_x" }
        assertFalse(
            "a legacy rule with no expected state must stay popup-only",
            generic.matches(observation(GameState.SEARCHING), RuleScope.ATTACK) { null }
        )
        assertNull(SafeActionFactory.resolve(observation(GameState.SEARCHING), generic).action)
    }

    // ---------- taught rules on ordinary screens ----------

    @Test
    fun `a taught rule can match a non-popup screen by hash within the same state`() {
        val rule = RecoveryRule(
            id = "taught_searching",
            name = "Searching",
            action = RecoveryActionType.WAIT,
            expectedState = GameState.SEARCHING,
            matchHash = "stored"
        )
        assertTrue(
            rule.matches(observation(GameState.SEARCHING, hash = "live"), RuleScope.ATTACK) { 2 }
        )
        assertFalse(
            "a hash beyond the taught distance must not match",
            rule.matches(observation(GameState.SEARCHING, hash = "live"), RuleScope.ATTACK) { 4 }
        )
        assertFalse(
            "the same hash on a different state must not match",
            rule.matches(observation(GameState.BATTLE, hash = "live"), RuleScope.ATTACK) { 2 }
        )
    }

    @Test
    fun `a non-popup tap rule still cannot press a never-press box`() {
        val gem = ScreenBox(200, 100, 300, 200, kind = "gem")
        val rule = RecoveryRule(
            id = "bad_point",
            name = "Bad point",
            action = RecoveryActionType.TAP_POINT,
            expectedState = GameState.SEARCHING,
            tapX = 0.25f,
            tapY = 0.30f
        )
        val attempt = SafeActionFactory.resolve(
            observation(GameState.SEARCHING, forbidden = listOf(gem)),
            rule
        )
        assertNull("a non-popup tap on a gem box must be refused", attempt.action)
        assertNotNull(attempt.refusal)
    }
}
