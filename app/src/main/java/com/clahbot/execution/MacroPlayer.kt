package com.clahbot.execution

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.clahbot.data.AppEventLog
import com.clahbot.data.BotState
import com.clahbot.data.ControllerState
import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.results.ResultCaptureGate
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Replays a macro through the accessibility service.
 *
 * Only one player owns the gesture pipeline at a time; starting a new macro
 * cancels whatever was running so a stale loop cannot keep injecting touches.
 */
class MacroPlayer(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val resultGate: ResultCaptureGate? = null
) {

    private var job: Job? = null

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    fun play(macro: Macro, scope: CoroutineScope, onFinished: (() -> Unit)? = null) {
        active?.let { if (it !== this) it.stop() }
        stopInternal()

        val gestures = macro.gestures.sortedBy { it.timestampMs }
        if (gestures.isEmpty()) {
            BotState.setState(ControllerState.ERROR)
            BotState.setStatus("\"${macro.name}\" has no gestures")
            return
        }
        if (ClahAccessibilityService.instance == null) {
            BotState.setState(ControllerState.ERROR)
            BotState.setStatus("Accessibility service not enabled")
            return
        }

        stopped = false
        paused = false
        BotState.setRepeatCountdown(null)
        active = this
        BotState.setActiveMacro(macro.id, macro.name)
        BotState.setState(ControllerState.PLAYING)
        BotState.setStatus("Starting \"${macro.name}\"")

        val newJob = scope.launch {
            val self = coroutineContext[Job]
            var iteration = 0
            try {
                val repeats = MacroTimeline.repeatCount(macro.repeatMode)

                while (isActive && !stopped && iteration < repeats) {
                    iteration++
                    AppEventLog.log(
                        "macro iteration=$iteration " +
                            "repeats=${if (repeats == Int.MAX_VALUE) "infinite" else repeats}"
                    )
                    BotState.setStatus(
                        if (repeats == Int.MAX_VALUE) "Loop $iteration" else "Run $iteration/$repeats"
                    )

                    var lastTimestamp = 0L
                    for (gesture in gestures) {
                        if (!isActive || stopped) break
                        ensureForeground()

                        val gap = MacroTimeline.gapMillis(
                            (gesture.timestampMs - lastTimestamp).coerceAtLeast(0L),
                            macro.speedMultiplier
                        )
                        if (gap > 0) delay(gap)

                        val service = ClahAccessibilityService.instance
                        if (service == null) {
                            BotState.setState(ControllerState.ERROR)
                            BotState.setStatus("Accessibility service disconnected")
                            return@launch
                        }

                        if (shouldGateTap(macro, gesture)) {
                            val engaged = resultGate?.awaitClearance() ?: false
                            AppEventLog.log(
                                "gate tap at ${gesture.timestampMs}ms engaged=$engaged " +
                                    "(${resultGate?.snapshot() ?: "-"})"
                            )
                        }

                        if (!dispatch(service, gesture, macro.speedMultiplier) && !stopped) {
                            BotState.setStatus("Gesture cancelled by system")
                        }
                        lastTimestamp = gesture.timestampMs
                    }

                    if (iteration < repeats && !stopped) {
                        ensureForeground()
                        waitBetweenRepeats(
                            totalMs = macro.effectiveRepeatDelayMs,
                            infinite = repeats == Int.MAX_VALUE
                        )
                    }
                }

                if (!stopped) {
                    BotState.clearActiveMacro()
                    BotState.setState(ControllerState.READY)
                    BotState.setStatus("Playback complete")
                    onFinished?.invoke()
                }
            } catch (_: CancellationException) {
                // Stopping is a normal exit path.
            } finally {
                AppEventLog.log("macro loop exit iteration=$iteration stopped=$stopped")
                BotState.setRepeatCountdown(null)
                if (job === null || job === self) {
                    job = null
                    if (active === this@MacroPlayer) active = null
                }
            }
        }
        job = newJob
    }

    private fun shouldGateTap(macro: Macro, gesture: RecordedGesture): Boolean =
        resultGate != null &&
            gesture.gestureType == "tap" &&
            gesture.timestampMs >= macro.durationMs - LATE_TAP_WINDOW_MS

    private suspend fun ensureForeground() {
        if (!ClahAccessibilityService.isGameForeground()) {
            if (!paused) {
                BotState.setInterrupted(true)
                paused = true
                BotState.setState(ControllerState.PLAYBACK_PAUSED)
                BotState.setStatus(pauseReason())
            }
        }
        awaitResume()
    }

    /**
     * Waits between repetitions, publishing the remaining whole seconds for the
     * controller. Foreground loss pauses the wait; stopping cancels it.
     */
    private suspend fun waitBetweenRepeats(totalMs: Long, infinite: Boolean) {
        if (totalMs <= 0L) return
        var remaining = totalMs
        try {
            while (remaining > 0L && !stopped) {
                ensureForeground()
                if (stopped) break
                if (totalMs >= COUNTDOWN_MIN_MS) {
                    val seconds = ((remaining + 999L) / 1000L).toInt()
                    BotState.setRepeatCountdown(seconds)
                    BotState.setStatus(
                        "Next " + (if (infinite) "loop" else "run") + " in ${seconds}s"
                    )
                }
                val step = minOf(COUNTDOWN_TICK_MS, remaining)
                delay(step)
                remaining -= step
            }
        } finally {
            BotState.setRepeatCountdown(null)
        }
    }

    private fun pauseReason(): String {
        val front = ClahAccessibilityService.foregroundLabel()
        return if (front.startsWith("com.clahbot")) {
            "Paused — open Clash of Clans, then press Resume"
        } else {
            "Paused — " + front + " is in front"
        }
    }

    private suspend fun awaitResume() {
        while (paused && !stopped) delay(PAUSE_POLL_MS)
    }

    private suspend fun dispatch(
        service: ClahAccessibilityService,
        gesture: RecordedGesture,
        speed: Float
    ): Boolean {
        val paths = MacroTimeline.playbackPaths(
            gesture,
            speed,
            screenWidth,
            screenHeight
        )
        if (paths.isEmpty()) return false

        val description = try {
            val builder = GestureDescription.Builder()
            for (path in paths) {
                builder.addStroke(
                    GestureDescription.StrokeDescription(
                        buildPath(path.points, gesture.gestureType),
                        path.startOffsetMs,
                        path.durationMs.coerceAtLeast(1L)
                    )
                )
            }
            builder.build()
        } catch (_: Exception) {
            return false
        }
        val totalDuration = paths.maxOf { it.startOffsetMs + it.durationMs }

        // A cancelled or never-answered dispatch must not stall the loop forever.
        val result = withTimeoutOrNull(totalDuration + DISPATCH_TIMEOUT_SLACK_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                val accepted = service.dispatchGesture(description, callback, null)
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        }
        return result ?: false
    }

    private fun buildPath(points: List<GesturePoint>, type: String): Path {
        val path = Path()
        val first = points.first()
        path.moveTo(first.x, first.y)
        if (type != "swipe" || points.size < 2) return path
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        return path
    }

    fun pause() {
        if (job == null) return
        paused = true
        BotState.setState(ControllerState.PLAYBACK_PAUSED)
        BotState.setStatus("Paused")
    }

    fun resume() {
        if (job == null) return
        paused = false
        BotState.setState(ControllerState.PLAYING)
        BotState.setStatus("Playing")
    }

    fun isPaused(): Boolean = paused

    fun isRunning(): Boolean = job != null

    fun stop() {
        stopInternal()
        BotState.clearActiveMacro()
        BotState.setState(ControllerState.STOPPING)
        BotState.setStatus("Playback stopped")
    }

    private fun stopInternal() {
        stopped = true
        paused = false
        BotState.setRepeatCountdown(null)
        job?.cancel()
        job = null
        if (active === this) active = null
    }

    companion object {
        private const val PAUSE_POLL_MS = 40L
        private const val COUNTDOWN_TICK_MS = 250L
        private const val COUNTDOWN_MIN_MS = 1_000L
        private const val DISPATCH_TIMEOUT_SLACK_MS = 1_500L
        private const val LATE_TAP_WINDOW_MS = 15_000L

        @Volatile
        private var active: MacroPlayer? = null

        fun stopActive() {
            active?.stop()
        }

        fun hasActivePlayer(): Boolean = active != null
    }
}
