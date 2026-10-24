package com.clahbot.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveGestureBuilderTest {

    private val device = TouchscreenDevice(
        path = "/dev/input/event2",
        name = "test-touch",
        maxX = 1079,
        maxY = 2399,
        maxSlots = 9,
        direct = true
    )

    private val gestures = mutableListOf<BuiltGesture>()
    private var bounds: ScreenRect? = null
    private val builder = PassiveGestureBuilder(
        device = device,
        geometry = { ScreenGeometry(1080, 2400, 0) },
        controllerBounds = { bounds },
        onGesture = { gestures += it }
    )

    @Test
    fun tapKeepsItsRealDuration() {
        builder.onFrame(frame(1000, contact(0, 1, 300, 2300)))
        builder.onFrame(frame(1200))

        val gesture = gestures.single()
        assertEquals(1000L, gesture.startUptimeMs)
        assertEquals(200L, gesture.durationMs)
        assertEquals(1, gesture.paths.size)
        val path = gesture.primary
        assertEquals(1, path.pointerId)
        assertEquals(0L, path.startOffsetMs)
        assertEquals(200L, path.durationMs)
        assertEquals(2, path.points.size)
        assertPoint(path.points[0], 300f, 2300f, 0L)
        assertPoint(path.points[1], 300f, 2300f, 200L)
    }

    @Test
    fun dragKeepsEveryReportedPointAndItsTimestamp() {
        builder.onFrame(frame(2000, contact(0, 1, 400, 1500)))
        builder.onFrame(frame(2100, contact(0, 1, 500, 1400)))
        builder.onFrame(frame(2400, contact(0, 1, 800, 1200)))
        builder.onFrame(frame(2416))

        val gesture = gestures.single()
        assertEquals(2000L, gesture.startUptimeMs)
        assertEquals(416L, gesture.durationMs)
        val points = gesture.primary.points
        assertEquals(4, points.size)
        assertPoint(points[0], 400f, 1500f, 0L)
        assertPoint(points[1], 500f, 1400f, 100L)
        assertPoint(points[2], 800f, 1200f, 400L)
        assertPoint(points[3], 800f, 1200f, 416L)
    }

    @Test
    fun twoFingersProduceOneGestureWithTwoPaths() {
        builder.onFrame(frame(5000, contact(0, 1, 400, 1500)))
        builder.onFrame(
            frame(5100, contact(0, 1, 410, 1500), contact(1, 2, 600, 1500))
        )
        builder.onFrame(
            frame(5200, contact(0, 1, 450, 1400), contact(1, 2, 650, 1400))
        )
        builder.onFrame(frame(5300, contact(1, 2, 640, 1300)))
        builder.onFrame(frame(5400))

        val gesture = gestures.single()
        assertEquals(5000L, gesture.startUptimeMs)
        assertEquals(400L, gesture.durationMs)
        assertEquals(2, gesture.paths.size)

        val first = gesture.paths[0]
        assertEquals(1, first.pointerId)
        assertEquals(0L, first.startOffsetMs)
        assertEquals(300L, first.durationMs)
        assertPoint(first.points.first(), 400f, 1500f, 0L)
        assertPoint(first.points.last(), 450f, 1400f, 300L)

        val second = gesture.paths[1]
        assertEquals(2, second.pointerId)
        assertEquals(100L, second.startOffsetMs)
        assertEquals(300L, second.durationMs)
        assertPoint(second.points.first(), 600f, 1500f, 0L)
        assertPoint(second.points.last(), 640f, 1300f, 300L)
    }

    @Test
    fun touchesThatStartInsideTheControllerAreNotRecorded() {
        bounds = ScreenRect(0f, 0f, 500f, 400f)

        builder.onFrame(frame(1000, contact(0, 1, 100, 100)))
        builder.onFrame(frame(1100))
        builder.onFrame(frame(2000, contact(0, 2, 300, 2300)))
        builder.onFrame(frame(2200))

        val gesture = gestures.single()
        assertEquals(2000L, gesture.startUptimeMs)
        assertPoint(gesture.primary.points.first(), 300f, 2300f, 0L)
    }

    @Test
    fun aSecondFingerOnTheControllerDoesNotBecomeAPath() {
        bounds = ScreenRect(0f, 0f, 500f, 400f)

        builder.onFrame(frame(3000, contact(0, 1, 400, 1500)))
        builder.onFrame(
            frame(3100, contact(0, 1, 450, 1450), contact(1, 2, 100, 100))
        )
        builder.onFrame(
            frame(3200, contact(0, 1, 500, 1400), contact(1, 2, 100, 100))
        )
        builder.onFrame(frame(3300, contact(0, 1, 550, 1350)))
        builder.onFrame(frame(3400))

        val gesture = gestures.single()
        assertEquals(1, gesture.paths.size)
        assertEquals(1, gesture.primary.pointerId)
        assertEquals(400L, gesture.durationMs)
    }

    @Test
    fun aGestureCanStartWithAnIgnoredFingerAndACapturedFinger() {
        bounds = ScreenRect(0f, 0f, 500f, 400f)

        builder.onFrame(
            frame(1000, contact(0, 1, 100, 100), contact(1, 2, 300, 2300))
        )
        builder.onFrame(frame(1100, contact(1, 2, 310, 2300)))
        builder.onFrame(frame(1200))

        val gesture = gestures.single()
        assertEquals(1, gesture.paths.size)
        assertEquals(2, gesture.primary.pointerId)
        assertEquals(200L, gesture.durationMs)
        assertPoint(gesture.primary.points.first(), 300f, 2300f, 0L)
    }

    @Test
    fun disabledBuilderCancelsTheGestureAndIgnoresInputUntilResumed() {
        builder.onFrame(frame(1000, contact(0, 1, 300, 2300)))
        builder.setEnabled(false)
        builder.onFrame(frame(1100, contact(0, 1, 300, 2300)))
        builder.onFrame(frame(1200))
        assertTrue(gestures.isEmpty())

        builder.setEnabled(true)
        builder.onFrame(frame(2000, contact(0, 2, 400, 1500)))
        builder.onFrame(frame(2200))

        val gesture = gestures.single()
        assertEquals(2000L, gesture.startUptimeMs)
    }

    @Test
    fun cancelDropsTheGestureWithoutEmittingIt() {
        builder.onFrame(frame(1000, contact(0, 1, 300, 2300)))
        builder.cancel()
        builder.onFrame(frame(1200))
        assertTrue(gestures.isEmpty())

        builder.onFrame(frame(2000, contact(0, 2, 400, 1500)))
        builder.onFrame(frame(2200))
        assertEquals(1, gestures.size)
    }

    private fun contact(slot: Int, id: Int, x: Int, y: Int) =
        RawTouchContact(slot = slot, trackingId = id, rawX = x, rawY = y)

    private fun frame(timestampMs: Long, vararg contacts: RawTouchContact) =
        RawTouchFrame(timestampMs, contacts.toList())

    private fun assertPoint(point: com.clahbot.data.models.GesturePoint, x: Float, y: Float, t: Long) {
        assertEquals(x, point.x, 0.001f)
        assertEquals(y, point.y, 0.001f)
        assertEquals(t, point.t)
    }
}
