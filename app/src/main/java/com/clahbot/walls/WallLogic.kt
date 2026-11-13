package com.clahbot.walls

import com.clahbot.game.GameState

/**
 * Pure wall decisions shared by the Auto Walls runtime. Nothing here touches
 * Android, so every rule the automation follows is testable in one place.
 */
object WallLogic {

    /** Screens that mean the automation must stop immediately. */
    fun unsafeDuringRun(state: GameState?): Boolean = when (state) {
        GameState.POPUP, GameState.UNKNOWN, GameState.NOT_CLASH, GameState.BATTLE -> true
        else -> false
    }

    /**
     * Whether a second upgrade may be spent in the same home visit: the user
     * asked for repeats, the previous upgrade is proven by a balance drop, the
     * last run actually ran, and the runaway-spend ceiling is not reached.
     */
    fun mayRepeatUpgrade(
        status: String,
        allowed: Boolean,
        verified: Boolean,
        repeats: Int,
        maxRepeats: Int
    ): Boolean = status == "ran" && allowed && verified && repeats < maxRepeats
}
