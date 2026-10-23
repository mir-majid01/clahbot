package com.clahbot.execution

import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroTimelineTest {

    @Test
    fun gapIsScaledBySpeed() {
        assertEquals(1000L, MacroTimeline.gapMillis(1000, 1f))
        assertEquals(500L, MacroTimeline.gapMillis(1000, 2f))
        assertEquals(2000L, MacroTimeline.gapMillis(1000, 0.5f))
    }

    @Test
    fun gapIsNeverNegativeOrUnbounded() {
        assertEquals(0L, MacroTimeline.gapMillis(-500, 1f))
        assertEquals(MacroTimeline.MAX_GAP_MS, MacroTimeline.gapMillis(Long.MAX_VALUE, 0.25f))
    }

    @Test
    fun speedIsClampedAndStepped() {
        assertEquals(MacroTimeline.MIN_SPEED, MacroTimeline.clampSpeed(0f))
        assertEquals(MacroTimeline.MAX_SPEED, MacroTimeline.clampSpeed(99f))
        assertEquals(1f, MacroTimeline.clampSpeed(Float.NaN))
        assertEquals(1.25f, MacroTimeline.stepSpeed(1f, 1))
        assertEquals(0.75f, MacroTimeline.stepSpeed(1f, -1))
    }

    @Test
    fun strokeDurationRespectsMinimums() {
        val tap = gesture(type = "tap", duration = 50)
        assertEquals(50L, MacroTimeline.strokeDuration(tap, 1f))
        assertEquals(25L, MacroTimeline.strokeDuration(tap, 2f))
        assertEquals(MacroTimeline.MIN_TAP_STROKE_MS, MacroTimeline.strokeDuration(tap, 4f))

        val press = gesture(type = "long_press", duration = 500)
        assertEquals(MacroTimeline.MIN_LONG_PRESS_STROKE_MS, MacroTimeline.strokeDuration(press, 4f))
    }

    @Test
    fun repeatCountParsesModes() {
        assertEquals(Int.MAX_VALUE, MacroTimeline.repeatCount(Macro.REPEAT_INFINITE))
        assertEquals(1, MacroTimeline.repeatCount(Macro.REPEAT_ONCE))
        assertEquals(1, MacroTimeline.repeatCount(null))
        assertEquals(1, MacroTimeline.repeatCount("nonsense"))
        assertEquals(1, MacroTimeline.repeatCount("0"))
        assertEquals(4, MacroTimeline.repeatCount("4"))
    }

    @Test
    fun repeatCycleRotatesAndLabels() {
        assertEquals(Macro.REPEAT_INFINITE, MacroTimeline.nextRepeat(Macro.REPEAT_ONCE))
        assertEquals("3", MacroTimeline.nextRepeat(Macro.REPEAT_INFINITE))
        assertEquals(Macro.REPEAT_ONCE, MacroTimeline.nextRepeat("3"))
        assertEquals(Macro.REPEAT_ONCE, MacroTimeline.nextRepeat("unknown"))

        assertEquals("Once", MacroTimeline.repeatLabel(Macro.REPEAT_ONCE))
        assertEquals("Loop", MacroTimeline.repeatLabel(Macro.REPEAT_INFINITE))
        assertEquals("3×", MacroTimeline.repeatLabel("3"))
    }

    @Test
    fun classifySeparatesTapDragAndHold() {
        assertEquals("tap", MacroTimeline.classify(2f, 120))
        assertEquals("long_press", MacroTimeline.classify(2f, 600))
        assertEquals("swipe", MacroTimeline.classify(60f, 120))
    }

    @Test
    fun deviationUsesFurthestPoint() {
        val points = listOf(
            GesturePoint(0f, 0f, 0),
            GesturePoint(3f, 4f, 10),
            GesturePoint(1f, 1f, 20)
        )
        assertEquals(5f, MacroTimeline.maxDeviationPx(points), 0.001f)
    }

    @Test
    fun decimateKeepsEndpointsAndThinsDensePaths() {
        val dense = (0 until 200).map { GesturePoint(it.toFloat(), it.toFloat(), it.toLong()) }
        val thinned = MacroTimeline.decimate(dense, minDistancePx = 6f, maxPoints = 240)
        assertTrue(thinned.size < dense.size)
        assertEquals(dense.first(), thinned.first())
        assertEquals(dense.last(), thinned.last())
    }

    @Test
    fun decimateCapsPointCount() {
        val dense = (0 until 1000).map {
            GesturePoint((it % 7).toFloat() * 3f, it.toFloat(), it.toLong())
        }
        val thinned = MacroTimeline.decimate(dense, minDistancePx = 1f, maxPoints = 50)
        assertEquals(50, thinned.size)
        assertEquals(dense.first(), thinned.first())
        assertEquals(dense.last(), thinned.last())
    }

    @Test
    fun normalizeRoundTrips() {
        val points = listOf(GesturePoint(540f, 1200f, 0), GesturePoint(1080f, 2400f, 100))
        val normalized = MacroTimeline.normalize(points, 1080, 2400)
        assertEquals(0.5f, normalized[0].x, 0.001f)
        assertEquals(0.5f, normalized[0].y, 0.001f)

        val back = MacroTimeline.denormalize(normalized, 1080, 2400)
        assertEquals(540f, back[0].x, 0.5f)
        assertEquals(1200f, back[0].y, 0.5f)
    }

    @Test
    fun legacyGestureWithoutPathIsRebuilt() {
        val legacy = RecordedGesture(
            timestampMs = 0,
            gestureType = "swipe",
            durationMs = 200,
            normalizedStartX = 0.1f,
            normalizedStartY = 0.2f,
            normalizedEndX = 0.8f,
            normalizedEndY = 0.9f,
            points = emptyList()
        )
        val effective = MacroTimeline.effectivePoints(legacy)
        assertEquals(2, effective.size)
        assertEquals(0.1f, effective.first().x, 0.001f)
        assertEquals(0.9f, effective.last().y, 0.001f)
    }

    @Test
    fun totalDurationUsesLatestEnd() {
        val gestures = listOf(
            gesture(type = "tap", duration = 50, timestamp = 100),
            gesture(type = "swipe", duration = 300, timestamp = 500)
        )
        assertEquals(800L, MacroTimeline.totalDuration(gestures))
        assertEquals(0L, MacroTimeline.totalDuration(emptyList()))
    }

    private fun gesture(
        type: String,
        duration: Long,
        timestamp: Long = 0L
    ) = RecordedGesture(
        timestampMs = timestamp,
        gestureType = type,
        durationMs = duration,
        points = emptyList()
    )
}
