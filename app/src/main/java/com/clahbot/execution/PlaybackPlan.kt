package com.clahbot.execution

import com.clahbot.data.models.Macro

/** Resolved playback limits for one run of a macro. */
data class PlaybackPlan(
    val iterations: Int,
    val countdownMs: Long,
    val stopOnBackground: Boolean,
    val stopAfterMinutes: Int?
) {
    val infinite: Boolean get() = iterations == Int.MAX_VALUE

    val deadlineMs: Long? get() = stopAfterMinutes?.let { it * 60_000L }
}

object PlaybackSettings {

    fun planFor(macro: Macro): PlaybackPlan {
        val repeats = MacroTimeline.repeatCount(macro.repeatMode)
        val attackLimit = macro.effectiveStopAfterAttacks ?: Int.MAX_VALUE
        return PlaybackPlan(
            iterations = minOf(repeats, attackLimit),
            countdownMs = macro.effectiveStartCountdownMs,
            stopOnBackground = macro.stopOnBackground,
            stopAfterMinutes = macro.effectiveStopAfterMinutes
        )
    }

    fun deadlineReached(plan: PlaybackPlan, elapsedMs: Long): Boolean =
        plan.deadlineMs?.let { elapsedMs >= it } ?: false
}
