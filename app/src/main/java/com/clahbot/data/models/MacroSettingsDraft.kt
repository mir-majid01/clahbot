package com.clahbot.data.models

import com.clahbot.execution.MacroTimeline

/**
 * The one editable macro-settings state used by the dashboard sheet and the
 * floating controller. Both surfaces save through [toMacro], so Speed, Repeat,
 * delay, Countdown, stop conditions and Pin can never drift apart.
 */
data class MacroSettingsDraft(
    var name: String,
    var speed: Float,
    var repeat: String,
    var repeatCount: Int,
    var delayMs: Long,
    var countdownSec: Int,
    var stopOnBackground: Boolean,
    var stopMinutes: Int?,
    var stopAttacks: Int?,
    var pinned: Boolean
) {

    fun repeatMode(): String =
        if (MacroTimeline.isCustomRepeat(repeat)) {
            MacroTimeline.customRepeatMode(repeatCount)
        } else {
            repeat
        }

    fun toMacro(base: Macro): Macro = base.copy(
        name = name.trim(),
        speedMultiplier = MacroTimeline.clampSpeed(speed),
        repeatMode = repeatMode(),
        repeatDelayMs = delayMs.coerceIn(0L, Macro.MAX_REPEAT_DELAY_MS),
        startCountdownMs = countdownSec.coerceIn(0, 30) * 1000L,
        stopOnBackground = stopOnBackground,
        stopAfterMinutes = stopMinutes,
        stopAfterAttacks = stopAttacks,
        pinned = pinned
    )

    companion object {
        fun from(macro: Macro): MacroSettingsDraft = MacroSettingsDraft(
            name = macro.name,
            speed = MacroTimeline.clampSpeed(macro.speedMultiplier),
            repeat = macro.repeatMode,
            repeatCount = if (MacroTimeline.isCustomRepeat(macro.repeatMode)) {
                MacroTimeline.repeatCount(macro.repeatMode)
            } else {
                10
            },
            delayMs = macro.effectiveRepeatDelayMs,
            countdownSec = (macro.effectiveStartCountdownMs / 1000L).toInt(),
            stopOnBackground = macro.stopOnBackground,
            stopMinutes = macro.effectiveStopAfterMinutes,
            stopAttacks = macro.effectiveStopAfterAttacks,
            pinned = macro.pinned
        )

        val DEFAULTS: MacroSettingsDraft = MacroSettingsDraft(
            name = "",
            speed = 1f,
            repeat = Macro.REPEAT_ONCE,
            repeatCount = 10,
            delayMs = Macro.LEGACY_REPEAT_DELAY_MS,
            countdownSec = 0,
            stopOnBackground = true,
            stopMinutes = null,
            stopAttacks = null,
            pinned = false
        )
    }
}
