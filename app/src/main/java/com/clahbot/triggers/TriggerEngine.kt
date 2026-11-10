package com.clahbot.triggers

import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation

/**
 * The decision half of image triggers. Pure: it matches enabled triggers in
 * priority order, resolves the chosen action into frame-pixel points, and
 * applies the never-press guard. It never dispatches a gesture or reads the
 * device — the caller executes an [Decision.Act].
 *
 * A match that would press a forbidden box (Wall Ring / buy / gems / surrender)
 * is returned as a [Decision.Refused], and the search stops there rather than
 * falling through to a lower-priority trigger that might tap.
 */
object TriggerEngine {

    data class Point(val x: Float, val y: Float)

    sealed interface Decision {
        data class Act(
            val trigger: ImageTrigger,
            val action: TriggerAction,
            val match: TemplateMatcher.Match,
            val start: Point?,
            val end: Point?
        ) : Decision

        data class Refused(
            val trigger: ImageTrigger,
            val reason: String,
            val match: TemplateMatcher.Match?
        ) : Decision

        object None : Decision
    }

    fun decide(
        frame: TemplateMatcher.Frame,
        templateFor: (ImageTrigger) -> TemplateMatcher.Frame?,
        observation: ScreenObservation,
        triggers: List<ImageTrigger>,
        playerRunning: Boolean
    ): Decision {
        val ordered = triggers.filter { it.enabled }.sortedByDescending { it.priority }
        for (trigger in ordered) {
            if (playerRunning && trigger.playbackMode == PlaybackMode.IDLE_ONLY) continue
            val template = templateFor(trigger) ?: continue
            val match = TemplateMatcher.match(
                frame,
                template,
                TemplateMatcher.Config(threshold = trigger.threshold)
            ) ?: continue
            return resolve(trigger, match, observation, playerRunning)
        }
        return Decision.None
    }

    private fun resolve(
        trigger: ImageTrigger,
        match: TemplateMatcher.Match,
        observation: ScreenObservation,
        playerRunning: Boolean
    ): Decision {
        val action = trigger.action
        return when (action.type) {
            TriggerActionType.RUN_MACRO ->
                if (playerRunning) {
                    Decision.Refused(trigger, "a macro is already running", match)
                } else {
                    Decision.Act(trigger, action, match, null, null)
                }

            TriggerActionType.STOP, TriggerActionType.WAIT, TriggerActionType.WAIT_UNTIL ->
                Decision.Act(trigger, action, match, null, null)

            TriggerActionType.TAP, TriggerActionType.LONG_PRESS -> {
                val point = action.point ?: return Decision.Refused(trigger, "no tap point", match)
                val start = pointOf(point, match, observation)
                if (observation.forbidden.any { it.intersects(boxAround(start, observation)) }) {
                    Decision.Refused(trigger, "target overlaps a never-press box", match)
                } else {
                    Decision.Act(trigger, action, match, start, null)
                }
            }

            TriggerActionType.SWIPE -> {
                val startPoint = action.point ?: return Decision.Refused(trigger, "no swipe start", match)
                val endPoint = action.endPoint ?: return Decision.Refused(trigger, "no swipe end", match)
                val start = pointOf(startPoint, match, observation)
                val end = pointOf(endPoint, match, observation)
                val startBox = boxAround(start, observation)
                val endBox = boxAround(end, observation)
                if (observation.forbidden.any { it.intersects(startBox) || it.intersects(endBox) }) {
                    Decision.Refused(trigger, "swipe touches a never-press box", match)
                } else {
                    Decision.Act(trigger, action, match, start, end)
                }
            }
        }
    }

    private fun pointOf(
        point: TriggerPoint,
        match: TemplateMatcher.Match,
        observation: ScreenObservation
    ): Point = if (point.relativeToMatch) {
        Point(match.left + point.x * match.width, match.top + point.y * match.height)
    } else {
        Point(point.x * observation.frameWidth, point.y * observation.frameHeight)
    }

    private fun boxAround(point: Point, observation: ScreenObservation): ScreenBox {
        val rx = (observation.frameWidth / 90).coerceAtLeast(4)
        val ry = (observation.frameHeight / 50).coerceAtLeast(4)
        val x = point.x.toInt()
        val y = point.y.toInt()
        return ScreenBox(x - rx, y - ry, x + rx, y + ry)
    }
}
