package com.clahbot.data

import com.clahbot.data.models.GesturePath
import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.execution.MacroTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroPathTest {

    @Test
    fun sanitizeKeepsTwoSimultaneousPaths() {
        val primary = listOf(
            GesturePoint(0.1f, 0.2f, 0),
            GesturePoint(0.3f, 0.4f, 300)
        )
        val secondary = listOf(
            GesturePoint(0.5f, 0.6f, 0),
            GesturePoint(0.7f, 0.8f, 300)
        )
        val macro = Macro(
            name = "multi",
            gestures = listOf(
                RecordedGesture(
                    timestampMs = 10,
                    gestureType = "swipe",
                    durationMs = 400,
                    points = primary,
                    paths = listOf(
                        GesturePath(0, primary),
                        GesturePath(100, secondary)
                    )
                )
            )
        )

        val sanitized = sanitizeMacro(macro)

        assertNotNull(sanitized)
        val gesture = sanitized!!.gestures.single()
        assertEquals(2, gesture.paths.size)
        assertEquals(100L, gesture.paths[1].startOffsetMs)
        assertEquals(0.7f, gesture.paths[1].points.last().x, 0.001f)
        assertEquals(400L, gesture.durationMs)
        assertEquals(410L, sanitized.durationMs)
    }

    @Test
    fun aSingleValidStoredPathFallsBackToThePrimaryPoints() {
        val primary = listOf(
            GesturePoint(0.1f, 0.2f, 0),
            GesturePoint(0.3f, 0.4f, 300)
        )
        val macro = Macro(
            name = "incomplete-multi",
            gestures = listOf(
                RecordedGesture(
                    timestampMs = 0,
                    gestureType = "swipe",
                    durationMs = 300,
                    points = primary,
                    paths = listOf(
                        GesturePath(50, emptyList()),
                        GesturePath(0, listOf(GesturePoint(0.5f, 0.5f, 0)))
                    )
                )
            )
        )

        val sanitized = sanitizeMacro(macro)

        assertNotNull(sanitized)
        val gesture = sanitized!!.gestures.single()
        assertTrue(gesture.paths.isEmpty())
        assertEquals(2, gesture.points.size)
        assertEquals(0.3f, gesture.points.last().x, 0.001f)
    }

    @Test
    fun pathTimesAndOffsetsAreClamped() {
        val primary = listOf(
            GesturePoint(0.1f, 0.2f, 0),
            GesturePoint(0.3f, 0.4f, 300)
        )
        val macro = Macro(
            name = "clamped",
            gestures = listOf(
                RecordedGesture(
                    timestampMs = 0,
                    gestureType = "swipe",
                    durationMs = 300,
                    points = primary,
                    paths = listOf(
                        GesturePath(0, primary),
                        GesturePath(
                            -50,
                            listOf(
                                GesturePoint(-1f, 2f, -5),
                                GesturePoint(0.7f, 0.8f, Long.MAX_VALUE)
                            )
                        )
                    )
                )
            )
        )

        val gesture = sanitizeMacro(macro)!!.gestures.single()

        assertEquals(2, gesture.paths.size)
        assertEquals(0L, gesture.paths[1].startOffsetMs)
        val first = gesture.paths[1].points.first()
        assertEquals(0f, first.x, 0.001f)
        assertEquals(1f, first.y, 0.001f)
        assertEquals(0L, first.t)
        assertEquals(
            MacroTimeline.MAX_STROKE_MS,
            gesture.paths[1].points.last().t
        )
    }
}
