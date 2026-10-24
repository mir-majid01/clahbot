package com.clahbot.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGestureTrackerTest {

    @Test
    fun tapDoesNotRequestRelayAndKeepsReleaseDuration() {
        val tracker = LiveGestureTracker(touchSlopPx = 24f)
        tracker.begin(300f, 2300f, 1_000L)

        val snapshot = tracker.finish(300f, 2300f, 1_400L)

        requireNotNull(snapshot)
        assertFalse(snapshot.relayRequested)
        assertEquals(400L, snapshot.durationMs)
        assertEquals(300f, snapshot.points.first().x, 0.01f)
        assertEquals(2300f, snapshot.points.first().y, 0.01f)
        assertEquals(0L, snapshot.points.first().t)
        assertEquals(400L, snapshot.points.last().t)
    }

    @Test
    fun dragRequestsRelayOnlyAfterSlopIsCrossed() {
        val tracker = LiveGestureTracker(touchSlopPx = 24f)
        tracker.begin(400f, 1500f, 0L)

        assertFalse(tracker.addPoint(410f, 1500f, 16L))
        assertTrue(tracker.addPoint(430f, 1500f, 32L))
        assertFalse(tracker.addPoint(500f, 1400f, 100L))

        val snapshot = tracker.finish(800f, 1200f, 400L)

        requireNotNull(snapshot)
        assertTrue(snapshot.relayRequested)
        assertEquals(400L, snapshot.durationMs)
        assertEquals(400f, snapshot.points.first().x, 0.01f)
        assertEquals(800f, snapshot.points.last().x, 0.01f)
        assertEquals(1200f, snapshot.points.last().y, 0.01f)
    }

    @Test
    fun interactionEndClosesADragWithoutAnUpEvent() {
        val tracker = LiveGestureTracker(touchSlopPx = 24f)
        tracker.begin(400f, 1500f, 2_000L)
        tracker.addPoint(500f, 1400f, 2_100L)

        val snapshot = tracker.finish(500f, 1400f, 2_400L)

        requireNotNull(snapshot)
        assertEquals(400L, snapshot.durationMs)
        assertEquals(400L, snapshot.points.last().t)
    }

    @Test
    fun cancelDropsTheGesture() {
        val tracker = LiveGestureTracker(touchSlopPx = 24f)
        tracker.begin(100f, 100f, 0L)
        tracker.addPoint(200f, 100f, 50L)
        tracker.cancel()

        assertFalse(tracker.isActive)
        assertNull(tracker.finish(200f, 100f, 100L))
    }
}
