package com.clahbot.execution

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.clahbot.data.BotState
import com.clahbot.data.ControllerState
import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
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
    private val screenHeight: Int
) {

    private var job: Job? = null

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    fun play(macro: Macro, scope: CoroutineScope) {
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
        active = this
        BotState.setActiveMacro(macro.id, macro.name)
        BotState.setState(ControllerState.PLAYING)
        BotState.setStatus("Starting \"${macro.name}\"")

        val newJob = scope.launch {
            val self = coroutineContext[Job]
            try {
                val repeats = MacroTimeline.repeatCount(macro.repeatMode)
                var iteration = 0

                while (isActive && !stopped && iteration < repeats) {
                    iteration++
                    BotState.setStatus(
                        if (repeats == Int.MAX_VALUE) "Loop $iteration" else "Run $iteration/$repeats"
                    )

                    var lastTimestamp = 0L
                    for (gesture in gestures) {
                        if (!isActive || stopped) break
                        awaitResume()

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

                        if (!dispatch(service, gesture, macro.speedMultiplier) && !stopped) {
                            BotState.setStatus("Gesture cancelled by system")
                        }
                        lastTimestamp = gesture.timestampMs
                    }

                    if (iteration < repeats && !stopped) {
                        awaitResume()
                        delay(INTER_LOOP_DELAY_MS)
                    }
                }

                if (!stopped) {
                    BotState.clearActiveMacro()
                    BotState.setState(ControllerState.READY)
                    BotState.setStatus("Playback complete")
                }
            } catch (_: CancellationException) {
                // Stopping is a normal exit path.
            } finally {
                if (job === null || job === self) {
                    job = null
                    if (active === this@MacroPlayer) active = null
                }
            }
        }
        job = newJob
    }

    private suspend fun awaitResume() {
        while (paused && !stopped) delay(PAUSE_POLL_MS)
    }

    private suspend fun dispatch(
        service: ClahAccessibilityService,
        gesture: RecordedGesture,
        speed: Float
    ): Boolean {
        val points = MacroTimeline.effectivePoints(gesture)
        if (points.isEmpty()) return false
        val path = buildPath(points, gesture.gestureType)
        val duration = MacroTimeline.strokeDuration(gesture, speed)

        // A cancelled or never-answered dispatch must not stall the loop forever.
        val result = withTimeoutOrNull(duration + DISPATCH_TIMEOUT_SLACK_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
                val description = GestureDescription.Builder().addStroke(stroke).build()
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
        val px = MacroTimeline.denormalize(points, screenWidth, screenHeight)
        val path = Path()
        val first = px.first()
        path.moveTo(first.x, first.y)
        if (type != "swipe" || px.size < 2) return path
        for (i in 1 until px.size) {
            path.lineTo(px[i].x, px[i].y)
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
        job?.cancel()
        job = null
        if (active === this) active = null
    }

    companion object {
        private const val INTER_LOOP_DELAY_MS = 800L
        private const val PAUSE_POLL_MS = 40L
        private const val DISPATCH_TIMEOUT_SLACK_MS = 1_500L

        @Volatile
        private var active: MacroPlayer? = null

        fun stopActive() {
            active?.stop()
        }

        fun hasActivePlayer(): Boolean = active != null
    }
}
