package com.clahbot.triggers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.clahbot.data.AppEventLog
import com.clahbot.game.GameInput
import com.clahbot.game.GameState
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import com.clahbot.gesture.GestureAuthority
import com.clahbot.gesture.CancelToken
import com.clahbot.gesture.GestureCommand
import com.clahbot.gesture.GesturePriority
import com.clahbot.gesture.GestureResult
import com.clahbot.gesture.ScreenOwnership
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The device half of image triggers: loads each reference PNG, matches it
 * against the live frame off the main thread, and dispatches the chosen
 * gesture through [GestureAuthority]. It only ever acts on a
 * [TriggerEngine.Decision.Act]; refusals and unmatched screens are recorded,
 * never executed. Trigger evaluation is skipped while a popup or unknown screen
 * is up, because recovery owns those.
 */
class TriggerRuntime(
    private val repository: TriggerRepository,
    private val stopAutomation: () -> Unit,
    private val gestureAuthority: GestureAuthority,
    private val sessionId: Long = System.currentTimeMillis()
) {

    private var transactionId: Long = 0L
    private val templateCache = HashMap<String, TemplateMatcher.Frame>()

    /** Called from the watchdog each tick. Returns true when a trigger acted. */
    suspend fun evaluateAndRun(
        frame: ClahAccessibilityService.GameFrame,
        observation: ScreenObservation,
        playerRunning: Boolean
    ): Boolean {
        if (observation.state == GameState.POPUP || observation.state == GameState.UNKNOWN) {
            return false
        }
        val triggers = repository.all().filter { it.enabled }
        if (triggers.isEmpty()) return false

        val pixels = withContext(Dispatchers.Default) { toFrame(frame.bitmap) } ?: return false
        val decision = withContext(Dispatchers.Default) {
            TriggerEngine.decide(
                frame = pixels,
                templateFor = { trigger -> template(trigger) },
                observation = observation,
                triggers = triggers,
                playerRunning = playerRunning
            )
        }
        return when (decision) {
            is TriggerEngine.Decision.Act -> {
                execute(decision, frame, observation)
                true
            }

            is TriggerEngine.Decision.Refused -> {
                repository.noteMatch(decision.trigger.id, "refused: ${decision.reason}")
                AppEventLog.log("trigger ${decision.trigger.id} refused: ${decision.reason}")
                false
            }

            TriggerEngine.Decision.None -> false
        }
    }

    private suspend fun execute(
        act: TriggerEngine.Decision.Act,
        frame: ClahAccessibilityService.GameFrame,
        observation: ScreenObservation
    ) {
        val action = act.action
        when (action.type) {
            TriggerActionType.TAP -> {
                val p = act.start ?: return
                val delivered = dispatchTap(
                    p, coerceDuration(action.durationMs, 40L), frame, observation,
                    reason = "trigger:${act.trigger.id}:tap"
                )
                record(act, if (delivered) "acted: tap" else "tap gesture failed")
            }

            TriggerActionType.LONG_PRESS -> {
                val p = act.start ?: return
                val delivered = dispatchTap(
                    p, coerceDuration(action.durationMs, 600L), frame, observation,
                    reason = "trigger:${act.trigger.id}:long-press"
                )
                record(act, if (delivered) "acted: long press" else "long press gesture failed")
            }

            TriggerActionType.SWIPE -> {
                val start = act.start ?: return
                val end = act.end ?: return
                val delivered = dispatchSwipe(
                    start, end, coerceDuration(action.durationMs, 300L), frame, observation,
                    reason = "trigger:${act.trigger.id}:swipe"
                )
                record(act, if (delivered) "acted: swipe" else "swipe gesture failed")
            }

            TriggerActionType.WAIT -> {
                delay(action.durationMs.coerceAtLeast(0).toLong())
                record(act, "acted: wait")
            }

            TriggerActionType.STOP -> {
                stopAutomation()
                record(act, "acted: stop")
            }

            TriggerActionType.RUN_MACRO, TriggerActionType.WAIT_UNTIL ->
                record(act, "not available in this build")
        }
    }

    private fun record(act: TriggerEngine.Decision.Act, result: String) {
        repository.noteMatch(act.trigger.id, result)
        AppEventLog.log("trigger ${act.trigger.id}: $result")
    }

    private fun coerceDuration(value: Int, fallback: Long): Long =
        if (value > 0) value.toLong() else fallback

    private fun toFrame(bitmap: Bitmap): TemplateMatcher.Frame? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return TemplateMatcher.Frame(pixels, w, h)
    }

    private fun template(trigger: ImageTrigger): TemplateMatcher.Frame? {
        val file = repository.imageFile(trigger)
        val key = trigger.id + ":" + file.lastModified()
        templateCache[key]?.let { return it }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val frame = try {
            toFrame(bitmap)
        } finally {
            bitmap.recycle()
        }
        if (frame != null) templateCache[key] = frame
        return frame
    }

    private suspend fun dispatchTap(
        point: TriggerEngine.Point,
        durationMs: Long,
        frame: ClahAccessibilityService.GameFrame,
        observation: ScreenObservation,
        reason: String
    ): Boolean {
        val screen = GameInput.toScreen(point.x.toInt(), point.y.toInt(), frame)
        transactionId++
        val command = GestureCommand(
            kind = GestureCommand.Kind.Tap(
                ScreenBox(
                    left = screen.x - 10, top = screen.y - 10,
                    right = screen.x + 10, bottom = screen.y + 10
                )
            ),
            sessionId = sessionId,
            transactionId = transactionId,
            frameHash = observation.hash,
            captureTimestampMillis = frame.capturedAtMillis,
            expiresAtMillis = System.currentTimeMillis() + 5_000,
            priority = GesturePriority.TRIGGER,
            screenOwnership = ScreenOwnership.SHARED,
            cancelToken = CancelToken(),
            owner = "TriggerRuntime",
            reason = reason
        )
        val result = gestureAuthority.enqueueAndAwait(command)
        return result == GestureResult.Completed
    }

    private suspend fun dispatchSwipe(
        start: TriggerEngine.Point,
        end: TriggerEngine.Point,
        durationMs: Long,
        frame: ClahAccessibilityService.GameFrame,
        observation: ScreenObservation,
        reason: String
    ): Boolean {
        val from = GameInput.toScreen(start.x.toInt(), start.y.toInt(), frame)
        val to = GameInput.toScreen(end.x.toInt(), end.y.toInt(), frame)
        transactionId++
        val command = GestureCommand(
            kind = GestureCommand.Kind.Swipe(from.x, from.y, to.x, to.y, durationMs),
            sessionId = sessionId,
            transactionId = transactionId,
            frameHash = observation.hash,
            captureTimestampMillis = frame.capturedAtMillis,
            expiresAtMillis = System.currentTimeMillis() + 5_000,
            priority = GesturePriority.TRIGGER,
            screenOwnership = ScreenOwnership.SHARED,
            cancelToken = CancelToken(),
            owner = "TriggerRuntime",
            reason = reason
        )
        val result = gestureAuthority.enqueueAndAwait(command)
        return result == GestureResult.Completed
    }
}
