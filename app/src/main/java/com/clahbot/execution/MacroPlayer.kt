package com.clahbot.execution

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.clahbot.data.SharedVisionBuffer
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class MacroPlayer(
    private val currentWidth: Int,
    private val currentHeight: Int
) {
    private var playbackJob: Job? = null
    private var paused = false

    fun play(macro: Macro, scope: CoroutineScope) {
        playbackJob?.cancel()
        paused = false

        playbackJob = scope.launch(Dispatchers.Default) {
            SharedVisionBuffer.setRunning(true)
            SharedVisionBuffer.setStatus("Playing: ${macro.name}")

            val gestures = macro.gestures.sortedBy { it.timestampMs }
            if (gestures.isEmpty()) {
                SharedVisionBuffer.setStatus("No gestures to play")
                SharedVisionBuffer.setRunning(false)
                return@launch
            }

            val totalMs = gestures.last().timestampMs + gestures.last().durationMs
            val repeatCount = when (macro.repeatMode) {
                "infinite" -> Int.MAX_VALUE
                else -> (macro.repeatMode.toIntOrNull() ?: 1)
            }
            var repeat = 0

            while (isActive && !paused && repeat < repeatCount) {
                val service = ClahAccessibilityService.instance
                if (service == null) {
                    SharedVisionBuffer.setStatus("ERROR: Accessibility service not enabled")
                    return@launch
                }

                var lastTimestamp = 0L
                for (gesture in gestures) {
                    if (!isActive || paused) break

                    val delayMs = ((gesture.timestampMs - lastTimestamp) / macro.speedMultiplier).toLong()
                    if (delayMs > 0) delay(timeMillis = delayMs)

                    val startX = gesture.normalizedStartX * currentWidth
                    val startY = gesture.normalizedStartY * currentHeight
                    val endX = gesture.normalizedEndX * currentWidth
                    val endY = gesture.normalizedEndY * currentHeight

                    val maxOffsetPx = when (macro.randomOffset) {
                        "small" -> 4
                        "medium" -> 12
                        else -> 0
                    }
                    val offX = if (maxOffsetPx > 0) Random.nextInt(-maxOffsetPx, maxOffsetPx + 1) else 0
                    val offY = if (maxOffsetPx > 0) Random.nextInt(-maxOffsetPx, maxOffsetPx + 1) else 0

                    val scaledDuration = (gesture.durationMs / macro.speedMultiplier).toLong().coerceAtLeast(20)

                    when (gesture.gestureType) {
                        "swipe" -> executeGesture(service, startX + offX, startY + offY, endX + offX, endY + offY, scaledDuration)
                        "long_press" -> executeGesture(service, startX + offX, startY + offY, startX + offX, startY + offY, scaledDuration.coerceAtLeast(300))
                        else -> executeGesture(service, startX + offX, startY + offY, startX + offX, startY + offY, scaledDuration.coerceAtMost(80))
                    }

                    lastTimestamp = gesture.timestampMs
                }

                repeat++
                if (repeat < repeatCount && isActive && !paused) {
                    val label = if (repeatCount == Int.MAX_VALUE) "Repeat #$repeat" else "Repeat $repeat/$repeatCount"
                    SharedVisionBuffer.setStatus(label)
                    delay(1000)
                }
            }

            SharedVisionBuffer.setRunning(false)
            SharedVisionBuffer.setStatus("Playback complete")
        }
    }

    private fun executeGesture(
        service: ClahAccessibilityService,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        duration: Long
    ) {
        val path = Path().apply {
            moveTo(x0, y0)
            if (x0 != x1 || y0 != y1) lineTo(x1, y1)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var completed = false
        val lock = Object()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { synchronized(lock) { completed = true; lock.notify() } }
            override fun onCancelled(g: GestureDescription?) { synchronized(lock) { completed = true; lock.notify() } }
        }, null)
        synchronized(lock) {
            if (!completed) {
                try { (lock as java.lang.Object).wait(duration + 200) } catch (_: InterruptedException) {}
            }
        }
    }

    fun stop() {
        paused = true
        playbackJob?.cancel()
        playbackJob = null
        SharedVisionBuffer.setRunning(false)
        SharedVisionBuffer.setStatus("Playback stopped")
    }

    fun pause() {
        paused = true
        SharedVisionBuffer.setRunning(false)
        SharedVisionBuffer.setStatus("Playback paused")
    }
}
