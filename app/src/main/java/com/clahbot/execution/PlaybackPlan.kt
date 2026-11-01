package com.clahbot.execution

import com.clahbot.data.models.Macro

/** Resolved playback limits for one run of a macro. */
data class PlaybackPlan(
    val iterations: Int,
    val countdownMs: Long,
    val stopOnBackground: Boolean,
    val stopAfterMinutes: Int?,
    val stopAfterAttacks: Int?
) {
    val infinite: Boolean get() = iterations == Int.MAX_VALUE

    val deadlineMs: Long? get() = stopAfterMinutes?.let { it * 60_000L }
}

object PlaybackSettings {

    fun planFor(macro: Macro): PlaybackPlan {
        val repeats = MacroTimeline.repeatCount(macro.repeatMode)
        return PlaybackPlan(
            iterations = repeats,
            countdownMs = macro.effectiveStartCountdownMs,
            stopOnBackground = macro.stopOnBackground,
            stopAfterMinutes = macro.effectiveStopAfterMinutes,
            stopAfterAttacks = macro.effectiveStopAfterAttacks
        )
    }

    fun deadlineReached(plan: PlaybackPlan, elapsedMs: Long): Boolean =
        plan.deadlineMs?.let { elapsedMs >= it } ?: false
}
