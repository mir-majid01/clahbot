package com.clahbot.execution

import com.clahbot.data.models.GesturePoint
import kotlin.math.hypot

/**
 * Builds a gesture from raw touchscreen samples while deciding when the
 * physical pointer must start being relayed to the app underneath.
 *
 * Kept free of Android types so the timing / slop rules can be unit tested.
 */
class LiveGestureTracker(private val touchSlopPx: Float) {

    data class Snapshot(
        val startTimeMs: Long,
        val durationMs: Long,
        val points: List<GesturePoint>,
        val relayRequested: Boolean
    )

    private data class Sample(val x: Float, val y: Float, val timeMs: Long)

    private val samples = mutableListOf<Sample>()
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startTimeMs = 0L
    private var lastTimeMs = 0L
    private var relayRequested = false
    private var active = false

    val isActive: Boolean get() = active

    fun begin(x: Float, y: Float, eventTimeMs: Long) {
        samples.clear()
        startX = x
        startY = y
        lastX = x
        lastY = y
        startTimeMs = eventTimeMs
        lastTimeMs = eventTimeMs
        relayRequested = false
        active = true
        samples.add(Sample(x, y, eventTimeMs))
    }

    /** Adds a move sample. Returns true exactly once, when the drag slop is crossed. */
    fun addPoint(x: Float, y: Float, eventTimeMs: Long): Boolean {
        if (!active) return false
        lastX = x
        lastY = y
        lastTimeMs = maxOf(lastTimeMs, eventTimeMs)
        samples.add(Sample(x, y, lastTimeMs))
        if (!relayRequested &&
            hypot(x - startX, y - startY) >= touchSlopPx
        ) {
            relayRequested = true
            return true
        }
        return false
    }

    fun finish(endX: Float, endY: Float, endTimeMs: Long): Snapshot? {
        if (!active) return null
        val end = maxOf(endTimeMs, lastTimeMs)
        if (end > lastTimeMs || endX != lastX || endY != lastY) {
            samples.add(Sample(endX, endY, end))
            lastX = endX
            lastY = endY
            lastTimeMs = end
        }
        val duration = (end - startTimeMs).coerceAtLeast(1L)
        val points = samples.map { sample ->
            GesturePoint(
                sample.x,
                sample.y,
                (sample.timeMs - startTimeMs).coerceIn(0L, duration)
            )
        }
        val snapshot = Snapshot(startTimeMs, duration, points, relayRequested)
        reset()
        return snapshot
    }

    fun cancel() = reset()

    private fun reset() {
        active = false
        relayRequested = false
        samples.clear()
        startTimeMs = 0L
        lastTimeMs = 0L
    }
}
