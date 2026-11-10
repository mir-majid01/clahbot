package com.clahbot.triggers

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import com.clahbot.data.AppEventLog
import com.clahbot.game.GameInput
import com.clahbot.game.GameState
import com.clahbot.game.ScreenObservation
import com.clahbot.service.ClahAccessibilityService
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device half of image triggers: loads each reference PNG, matches it
 * against the live frame off the main thread, and dispatches the chosen
 * gesture. It only ever acts on a [TriggerEngine.Decision.Act]; refusals and
 * unmatched screens are recorded, never executed. Trigger evaluation is skipped
 * while a popup or unknown screen is up, because recovery owns those.
 */
class TriggerRuntime(
    private val repository: TriggerRepository,
    private val stopAutomation: () -> Unit
) {

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
                execute(decision, frame)
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

    private suspend fun execute(act: TriggerEngine.Decision.Act, frame: ClahAccessibilityService.GameFrame) {
        val action = act.action
        when (action.type) {
            TriggerActionType.TAP -> {
                val p = act.start ?: return
                dispatchTap(p, coerceDuration(action.durationMs, 40L), frame)
                record(act, "acted: tap")
            }

            TriggerActionType.LONG_PRESS -> {
                val p = act.start ?: return
                dispatchTap(p, coerceDuration(action.durationMs, 600L), frame)
                record(act, "acted: long press")
            }

            TriggerActionType.SWIPE -> {
                val start = act.start ?: return
                val end = act.end ?: return
                dispatchSwipe(start, end, coerceDuration(action.durationMs, 300L), frame)
                record(act, "acted: swipe")
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
        frame: ClahAccessibilityService.GameFrame
    ) {
        val screen = GameInput.toScreen(point.x.toInt(), point.y.toInt(), frame)
        val path = Path().apply {
            moveTo(screen.x.toFloat(), screen.y.toFloat())
            lineTo(screen.x.toFloat(), screen.y.toFloat())
        }
        dispatch(path, durationMs)
    }

    private suspend fun dispatchSwipe(
        start: TriggerEngine.Point,
        end: TriggerEngine.Point,
        durationMs: Long,
        frame: ClahAccessibilityService.GameFrame
    ) {
        val from = GameInput.toScreen(start.x.toInt(), start.y.toInt(), frame)
        val to = GameInput.toScreen(end.x.toInt(), end.y.toInt(), frame)
        val path = Path().apply {
            moveTo(from.x.toFloat(), from.y.toFloat())
            lineTo(to.x.toFloat(), to.y.toFloat())
        }
        dispatch(path, durationMs)
    }

    private suspend fun dispatch(path: Path, durationMs: Long) {
        val service = ClahAccessibilityService.instance ?: return
        val description = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
                .build()
        } catch (_: Exception) {
            return
        }
        withTimeoutOrNull(DISPATCH_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                val accepted = service.dispatchGesture(description, callback, null)
                if (!accepted && continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    companion object {
        private const val DISPATCH_TIMEOUT_MS = 2_000L
    }
}
