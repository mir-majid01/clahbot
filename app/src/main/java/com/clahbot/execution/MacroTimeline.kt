package com.clahbot.execution

import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import kotlin.math.hypot

/**
 * Pure timing / geometry rules shared by recording and playback. Kept free of
 * Android types so it can be unit tested on the JVM.
 */
object MacroTimeline {

    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4.0f
    const val SPEED_STEP = 0.25f

    const val DRAG_THRESHOLD_PX = 24f
    const val SAMPLE_DISTANCE_PX = 6f
    const val MAX_PATH_POINTS = 240
    const val MAX_STROKE_MS = 59_000L
    const val MAX_GAP_MS = 60_000L

    const val TAP_MS = 50L
    const val LONG_PRESS_MS = 500L
    const val MIN_TAP_STROKE_MS = 24L
    const val MIN_LONG_PRESS_STROKE_MS = 300L
    const val MIN_DRAG_STROKE_MS = 40L

    fun clampSpeed(speed: Float): Float {
        if (!speed.isFinite()) return 1f
        return speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun stepSpeed(current: Float, direction: Int): Float {
        return clampSpeed(current + direction * SPEED_STEP)
    }

    fun formatSpeed(speed: Float): String {
        val v = clampSpeed(speed)
        val rounded = Math.round(v * 100f)
        return if (rounded % 100 == 0) {
            "${rounded / 100}.0x"
        } else {
            String.format(java.util.Locale.US, "%.2fx", v)
        }
    }

    /** Recorded delay scaled by the speed factor. Never negative, never unbounded. */
    fun gapMillis(recordedGapMs: Long, speed: Float): Long {
        val factor = clampSpeed(speed)
        return (recordedGapMs / factor).toLong().coerceIn(0L, MAX_GAP_MS)
    }

    fun strokeDuration(gesture: RecordedGesture, speed: Float): Long {
        val factor = clampSpeed(speed)
        val scaled = (gesture.durationMs / factor).toLong()
        val min = when (gesture.gestureType) {
            "swipe" -> MIN_DRAG_STROKE_MS
            "long_press" -> MIN_LONG_PRESS_STROKE_MS
            else -> MIN_TAP_STROKE_MS
        }
        val max = when (gesture.gestureType) {
            "long_press", "swipe" -> MAX_STROKE_MS
            else -> 120L
        }
        return scaled.coerceIn(min, max)
    }

    fun repeatCount(repeatMode: String?): Int {
        if (repeatMode == null) return 1
        if (repeatMode == Macro.REPEAT_INFINITE) return Int.MAX_VALUE
        return repeatMode.trim().toIntOrNull()?.coerceIn(1, 10_000) ?: 1
    }

    /** Distance from the start point to the furthest sampled point, in pixels. */
    fun maxDeviationPx(points: List<GesturePoint>): Float {
        if (points.isEmpty()) return 0f
        val first = points[0]
        var max = 0f
        for (i in 1 until points.size) {
            val d = hypot(points[i].x - first.x, points[i].y - first.y)
            if (d > max) max = d
        }
        return max
    }

    fun classify(maxDeviationPx: Float, durationMs: Long): String = when {
        maxDeviationPx >= DRAG_THRESHOLD_PX -> "swipe"
        durationMs >= LONG_PRESS_MS -> "long_press"
        else -> "tap"
    }

    /**
     * Thins a dense MotionEvent stream without losing the shape of the path:
     * keeps every sample further than [minDistancePx] from the last kept one,
     * always keeps the first and last, then samples evenly if still too long.
     */
    fun decimate(
        points: List<GesturePoint>,
        minDistancePx: Float = SAMPLE_DISTANCE_PX,
        maxPoints: Int = MAX_PATH_POINTS
    ): List<GesturePoint> {
        if (points.size <= 2) return points.toList()

        val kept = ArrayList<GesturePoint>(points.size)
        kept.add(points.first())
        var lastX = points.first().x
        var lastY = points.first().y
        val minSq = minDistancePx * minDistancePx
        for (i in 1 until points.size - 1) {
            val p = points[i]
            val dx = p.x - lastX
            val dy = p.y - lastY
            if (dx * dx + dy * dy >= minSq) {
                kept.add(p)
                lastX = p.x
                lastY = p.y
            }
        }
        kept.add(points.last())

        if (kept.size <= maxPoints) return kept

        val evenly = ArrayList<GesturePoint>(maxPoints)
        val step = (kept.size - 1).toFloat() / (maxPoints - 1).toFloat()
        for (i in 0 until maxPoints) {
            evenly.add(kept[(i * step).toInt().coerceIn(0, kept.size - 1)])
        }
        evenly[0] = kept.first()
        evenly[maxPoints - 1] = kept.last()
        return evenly
    }

    /** Normalizes pixel points to 0..1 so macros survive a resolution change. */
    fun normalize(points: List<GesturePoint>, width: Int, height: Int): List<GesturePoint> {
        if (width <= 0 || height <= 0) return emptyList()
        val w = width.toFloat()
        val h = height.toFloat()
        return points.map {
            GesturePoint(
                x = (it.x / w).coerceIn(0f, 1f),
                y = (it.y / h).coerceIn(0f, 1f),
                t = it.t.coerceAtLeast(0L)
            )
        }
    }

    fun denormalize(points: List<GesturePoint>, width: Int, height: Int): List<GesturePoint> {
        val w = width.toFloat()
        val h = height.toFloat()
        return points.map {
            GesturePoint(
                x = (it.x * w).coerceIn(0f, w),
                y = (it.y * h).coerceIn(0f, h),
                t = it.t
            )
        }
    }

    /** Rebuilds a path for macros saved before paths were stored. */
    fun fallbackPoints(gesture: RecordedGesture): List<GesturePoint> {
        val start = GesturePoint(gesture.normalizedStartX, gesture.normalizedStartY, 0L)
        val end = GesturePoint(gesture.normalizedEndX, gesture.normalizedEndY, gesture.durationMs)
        return if (start == end) listOf(start) else listOf(start, end)
    }

    fun effectivePoints(gesture: RecordedGesture): List<GesturePoint> {
        return if (gesture.points.size >= 2) gesture.points else fallbackPoints(gesture)
    }

    fun totalDuration(gestures: List<RecordedGesture>): Long {
        return gestures.maxOfOrNull { it.timestampMs + it.durationMs } ?: 0L
    }
}
