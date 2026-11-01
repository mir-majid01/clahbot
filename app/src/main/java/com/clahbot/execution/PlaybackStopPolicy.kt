package com.clahbot.execution

/** The single rule for repeat plan + stop conditions. */
object PlaybackStopPolicy {

    data class Decision(val stop: Boolean, val reason: String?)

    /**
     * Called at the end of a completed run, after its Return Home. Time and
     * attacks are limits; the repeat mode is the plan. The earliest reached
     * limit wins. Stop and foreground loss are handled immediately elsewhere.
     */
    fun atRunEnd(
        repeatRuns: Int,
        runsCompleted: Int,
        attacks: Int,
        attackLimit: Int?,
        runningMs: Long,
        minuteLimit: Int?
    ): Decision {
        val minuteLimitMs = minuteLimit?.let { it * 60_000L }
        if (minuteLimitMs != null && runningMs >= minuteLimitMs) {
            return Decision(true, "Reached $minuteLimit min")
        }
        if (attackLimit != null && attacks >= attackLimit) {
            return Decision(true, "Reached $attackLimit attacks")
        }
        if (runsCompleted >= repeatRuns) {
            return Decision(true, "Repeat finished ($repeatRuns run${if (repeatRuns == 1) "" else "s"})")
        }
        return Decision(false, null)
    }
}

/** UI/status text for the repeat plan plus stop conditions. */
object StopSummary {

    fun summaryLine(macro: com.clahbot.data.models.Macro): String {
        val parts = mutableListOf<String>()
        macro.effectiveStopAfterAttacks?.let { parts += com.clahbot.util.Plural.attacks(it) }
        macro.effectiveStopAfterMinutes?.let { parts += "$it min" }
        if (macro.stopOnBackground) parts += "Clash leaves the screen"
        if (parts.isEmpty()) {
            return if (macro.repeatMode == com.clahbot.data.models.Macro.REPEAT_INFINITE) {
                "Loops until you stop."
            } else {
                val runs = macro.repeatMode.toIntOrNull()
                val runText = if (runs == null || runs == 1) "once" else {
                    com.clahbot.util.Plural.of(runs, "time")
                }
                "Runs $runText, then stops."
            }
        }
        return "Runs until the first of: " + parts.joinToString(" · ") +
            ". Attack and time limits take effect at the end of the current run."
    }

    fun conflictHint(macro: com.clahbot.data.models.Macro): String? {
        val attackLimit = macro.effectiveStopAfterAttacks ?: return null
        val runs = com.clahbot.execution.MacroTimeline.repeatCount(macro.repeatMode)
        if (runs == Int.MAX_VALUE) return null
        return if (runs < attackLimit) {
            "Repeat count $runs ends the run before " +
                com.clahbot.util.Plural.attacks(attackLimit) + "."
        } else {
            null
        }
    }

    fun playbackCaption(macro: com.clahbot.data.models.Macro): String {
        val parts = mutableListOf<String>()
        macro.effectiveStopAfterAttacks?.let { parts += com.clahbot.util.Plural.attacks(it) }
        macro.effectiveStopAfterMinutes?.let { parts += "$it min" }
        if (macro.stopOnBackground) parts += "Clash leaves the screen"
        return if (parts.isEmpty()) "Stop when you are ready" else
            "Stops after " + parts.joinToString(" · ")
    }
}
