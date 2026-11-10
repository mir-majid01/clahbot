package com.clahbot.walls

import com.clahbot.game.ScreenObservation
import com.clahbot.game.GameState
import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.execution.MacroTimeline
import com.clahbot.game.ScreenBox

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

    fun unsafeTaps(macro: Macro, observation: ScreenObservation): List<String> {
        if (observation.frameWidth <= 0 || observation.frameHeight <= 0) return listOf("no frame geometry")
        return macro.gestures.mapNotNull { unsafeGesture(it, macro.speedMultiplier, observation) }
    }

    fun unsafeGesture(gesture: RecordedGesture, speed: Float, observation: ScreenObservation): String? {
        val paths = MacroTimeline.playbackPaths(gesture, speed, observation.frameWidth, observation.frameHeight)
        if (paths.isEmpty()) return "no gesture geometry"
        if (paths.any { path -> path.points.any { !it.x.isFinite() || !it.y.isFinite() } }) {
            return "invalid gesture geometry"
        }
        val hit = firstHit(gesture.timestampMs, observation) { paths.map { it.points } }
        return if (hit != null) "gesture at ${gesture.timestampMs}ms hits a never-press box" else null
    }

    /** Where a refused gesture actually meets a never-press box. Diagnostics only. */
    data class ForbiddenHit(
        val gestureMs: Long,
        val kind: String,
        val box: ScreenBox,
        val x: Float,
        val y: Float
    )

    /**
     * The same decision [unsafeTaps] makes, but returning the first box that is
     * actually touched. A refusal can then be explained from the device log
     * instead of guessed at, without changing what is refused.
     */
    fun firstForbiddenHit(macro: Macro, observation: ScreenObservation): ForbiddenHit? =
        macro.gestures.firstNotNullOfOrNull { gesture ->
            val paths = MacroTimeline.playbackPaths(
                gesture,
                macro.speedMultiplier,
                observation.frameWidth,
                observation.frameHeight
            )
            val invalid = paths.isEmpty() || paths.any { path ->
                path.points.any { !it.x.isFinite() || !it.y.isFinite() }
            }
            if (invalid) null else firstHit(gesture.timestampMs, observation) { paths.map { it.points } }
        }

    /** One-line, log-friendly description of a refusal: state, frame, hit and boxes. */
    fun describeForbidden(observation: ScreenObservation, hit: ForbiddenHit?): String {
        val boxes = observation.forbidden.take(8).joinToString(";") { box ->
            "${box.kind.ifBlank { "box" }}:${box.left},${box.top}-${box.right},${box.bottom}"
        }
        val where = hit?.let {
            "hit=${it.kind.ifBlank { "box" }}@${it.box.left},${it.box.top}-${it.box.right},${it.box.bottom}" +
                " point=${it.x.toInt()},${it.y.toInt()} gesture=${it.gestureMs}ms"
        } ?: "hit=none"
        return "state=${observation.state} frame=${observation.frameWidth}x${observation.frameHeight} " +
            "forbidden=${observation.forbidden.size} $where boxes=[$boxes]"
    }

    private fun firstHit(
        gestureMs: Long,
        observation: ScreenObservation,
        pathsFor: () -> List<List<GesturePoint>>
    ): ForbiddenHit? {
        val paths = pathsFor()
        for (box in observation.forbidden) {
            for (path in paths) {
                path.firstOrNull {
                    it.x >= box.left && it.x <= box.right && it.y >= box.top && it.y <= box.bottom
                }?.let { return ForbiddenHit(gestureMs, box.kind, box, it.x, it.y) }
                for ((start, end) in path.zipWithNext()) {
                    if (crossesBox(start.x, start.y, end.x, end.y, box)) {
                        return ForbiddenHit(
                            gestureMs,
                            box.kind,
                            box,
                            (start.x + end.x) / 2f,
                            (start.y + end.y) / 2f
                        )
                    }
                }
            }
        }
        return null
    }

    private fun crossesBox(x1: Float, y1: Float, x2: Float, y2: Float, box: ScreenBox): Boolean {
        var enter = 0f
        var exit = 1f
        for ((start, delta, bounds) in listOf(
            Triple(x1, x2 - x1, box.left.toFloat() to box.right.toFloat()),
            Triple(y1, y2 - y1, box.top.toFloat() to box.bottom.toFloat())
        )) {
            if (delta == 0f) {
                if (start < bounds.first || start > bounds.second) return false
            } else {
                val first = (bounds.first - start) / delta
                val second = (bounds.second - start) / delta
                enter = maxOf(enter, minOf(first, second))
                exit = minOf(exit, maxOf(first, second))
                if (enter > exit) return false
            }
        }
        return true
    }

    /** Screens that mean the wall macro must stop immediately. */
    fun unsafeDuringRun(state: GameState?): Boolean = when (state) {
        GameState.POPUP, GameState.UNKNOWN, GameState.NOT_CLASH, GameState.BATTLE -> true
        else -> false
    }

    /** Screens that mean the village is back under control. */
    fun safeAfterRun(state: GameState?): Boolean = when (state) {
        GameState.HOME, GameState.ATTACK_MENU, GameState.SEARCHING, GameState.UPGRADE_PANEL -> true
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
