package com.clahbot.recovery

import com.clahbot.game.GameState
import com.clahbot.game.ScreenObservation

/**
 * Decides whether a running attack macro must wait on the current screen.
 *
 * A rule only gates playback when it is an enabled WAIT rule that waits for a
 * transition **and** names the non-popup screen state it expects. Popup and
 * unknown screens are the recovery engine's job, so gating never touches them.
 * This keeps the working attack playback unchanged unless the user deliberately
 * teaches a wait rule for the screen that is showing.
 */
object PlaybackGate {

    fun holdingRule(
        observation: ScreenObservation,
        rules: List<RecoveryRule>,
        scope: RuleScope = RuleScope.ATTACK,
        hashDistance: (String) -> Int?
    ): RecoveryRule? {
        if (observation.state == GameState.POPUP || observation.state == GameState.UNKNOWN) {
            return null
        }
        return rules.firstOrNull { rule ->
            rule.action == RecoveryActionType.WAIT &&
                rule.waitForTransition &&
                rule.matches(observation, scope, hashDistance)
        }
    }
}
