package com.clahbot.triggers

import com.clahbot.game.GameState

/** When a trigger may fire relative to a running macro. */
enum class PlaybackMode { DURING_AUTOMATION, IDLE_ONLY }

/** What a recognised image makes the app do. */
enum class TriggerActionType { TAP, LONG_PRESS, SWIPE, WAIT, RUN_MACRO, STOP, WAIT_UNTIL }

/**
 * A point an action presses. Fractions of the matched image when
 * [relativeToMatch] is true (so the press follows the icon wherever it appears),
 * otherwise fractions of the whole frame.
 */
data class TriggerPoint(
    val x: Float,
    val y: Float,
    val relativeToMatch: Boolean = true
)

/** WAIT_UNTIL: keep waiting until a screen state or another image trigger appears. */
data class WaitUntil(
    val state: GameState? = null,
    val triggerId: String? = null,
    val timeoutMs: Int = 15_000
)

/** One configured action fired by an image match. */
data class TriggerAction(
    val type: TriggerActionType,
    /** TAP / LONG_PRESS point, or SWIPE start. */
    val point: TriggerPoint? = null,
    /** SWIPE end point. */
    val endPoint: TriggerPoint? = null,
    /** LONG_PRESS hold or SWIPE duration; WAIT delay. */
    val durationMs: Int = 0,
    val repeat: Int = 1,
    val repeatGapMs: Int = 0,
    /** RUN_MACRO target; only run while no macro is already playing. */
    val macroId: Long? = null,
    val waitUntil: WaitUntil? = null
)

/**
 * A stored image trigger: a reference image plus the action to fire when it is
 * recognised on screen. The reference PNG lives in image_triggers/<imageFile>;
 * the metadata lives in triggers.json.
 */
data class ImageTrigger(
    val id: String,
    val name: String,
    val imageFile: String,
    /** Reference pixel size, for mapping stored fractions to the matched patch. */
    val refWidth: Int,
    val refHeight: Int,
    val threshold: Float = 0.85f,
    val action: TriggerAction,
    val enabled: Boolean = true,
    /** Higher runs first. */
    val priority: Int = 0,
    val playbackMode: PlaybackMode = PlaybackMode.DURING_AUTOMATION,
    val lastMatchedAt: Long? = null,
    val lastResult: String? = null,
    val matchCount: Int = 0
)
