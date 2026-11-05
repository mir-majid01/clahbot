package com.clahbot.walls

import com.clahbot.game.ScreenObservation
import com.clahbot.game.GameState
import com.clahbot.data.models.Macro

/**
 * Pure wall decisions. Nothing here touches Android, so every rule the
 * automation follows is testable and readable in one place.
 */
object WallLogic {

    /** The spendable part of a counter after the safety reserve. */
    fun spendable(balance: Long?, reserve: Long): Long =
        ((balance ?: 0L) - reserve).coerceAtLeast(0L)

    fun canAfford(balance: Long?, reserve: Long, cost: Long): Boolean =
        cost > 0L && spendable(balance, reserve) >= cost

    /**
     * Deterministic resource choice. BOTH mode checks each resource on its own
     * and uses the configured priority only when both can pay.
     */
    fun choose(
        gold: Long?,
        elixir: Long?,
        cost: Long,
        reserve: Long,
        mode: WallResourceMode,
        priority: WallPriority
    ): WallTrigger {
        if (cost <= 0L) return WallTrigger.NONE
        val goldOk = canAfford(gold, reserve, cost)
        val elixirOk = canAfford(elixir, reserve, cost)
        return when (mode) {
            WallResourceMode.GOLD -> if (goldOk) WallTrigger.GOLD else WallTrigger.NONE
            WallResourceMode.ELIXIR -> if (elixirOk) WallTrigger.ELIXIR else WallTrigger.NONE
            WallResourceMode.BOTH -> when {
                goldOk && elixirOk ->
                    if (priority == WallPriority.GOLD_FIRST) WallTrigger.GOLD else WallTrigger.ELIXIR
                goldOk -> WallTrigger.GOLD
                elixirOk -> WallTrigger.ELIXIR
                else -> WallTrigger.NONE
            }
        }
    }

    /**
     * Two reads count as one stable measurement only when the counter exists in
     * both and moved by at most 1% (collectors trickle in; the app is the only
     * spender between reads). A missing value in either read is unstable.
     */
    fun stable(first: Long?, second: Long?): Boolean {
        if (first == null || second == null) return false
        if (second < first) return false
        val drift = second - first
        return drift <= maxOf(1_000L, first / 100L)
    }

    fun stableReadings(
        goldFirst: Long?,
        goldSecond: Long?,
        elixirFirst: Long?,
        elixirSecond: Long?,
        mode: WallResourceMode
    ): Boolean = when (mode) {
        WallResourceMode.GOLD -> stable(goldFirst, goldSecond)
        WallResourceMode.ELIXIR -> stable(elixirFirst, elixirSecond)
        WallResourceMode.BOTH -> stable(goldFirst, goldSecond) || stable(elixirFirst, elixirSecond)
    }

    /** Screens the wall macro may never start on. */
    fun safeToStart(state: GameState?): Boolean = state == GameState.HOME

    /**
     * A wall macro is a recorded touch script. Before it runs, every tap is
     * checked against the current frame's never-press boxes: a recorded tap on
     * a gem price, Buy, Surrender or Shop control stops the automation.
     */
    fun unsafeTaps(macro: Macro, observation: ScreenObservation): List<String> {
        val frameW = observation.frameWidth
        val frameH = observation.frameHeight
        if (frameW <= 0 || frameH <= 0) return listOf("no frame geometry")
        val reasons = mutableListOf<String>()
        for (gesture in macro.gestures) {
            if (gesture.gestureType != "tap") continue
            val x = (gesture.normalizedStartX * frameW).toInt()
            val y = (gesture.normalizedStartY * frameH).toInt()
            val hit = observation.forbidden.firstOrNull { it.contains(x, y) }
            if (hit != null) {
                reasons += "tap at ${gesture.timestampMs}ms hits a never-press box"
            }
        }
        return reasons
    }

    /** Screens that mean the wall macro must stop immediately. */
    fun unsafeDuringRun(state: GameState?): Boolean = when (state) {
        GameState.POPUP, GameState.UNKNOWN, GameState.NOT_CLASH, GameState.BATTLE -> true
        else -> false
    }

    /** Screens that mean the village is back under control. */
    fun safeAfterRun(state: GameState?): Boolean = when (state) {
        GameState.HOME, GameState.ATTACK_MENU, GameState.SEARCHING -> true
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
