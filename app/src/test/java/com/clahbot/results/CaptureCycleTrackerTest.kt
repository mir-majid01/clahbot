package com.clahbot.results

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureCycleTrackerTest {

    @Test
    fun aNewResultIsCapturedOnce() {
        val tracker = CaptureCycleTracker()
        assertEquals(PollAction.CAPTURE, tracker.onPoll(results = true, hash = "a"))
        tracker.onRecorded("a")
        assertEquals(PollAction.ALREADY_RECORDED, tracker.onPoll(results = true, hash = "a"))
    }

    @Test
    fun aDifferentFrameOfTheSameScreenDoesNotCreateASecondRecordBeforeTheScreenClears() {
        val tracker = CaptureCycleTracker()
        tracker.onPoll(results = true, hash = "a")
        tracker.onRecorded("a")
        assertEquals(PollAction.WAIT_FOR_CLEAR, tracker.onPoll(results = true, hash = "b"))
    }

    @Test
    fun afterTheScreenClearsANewAttackCanBeCaptured() {
        val tracker = CaptureCycleTracker()
        tracker.onPoll(results = true, hash = "a")
        tracker.onRecorded("a")
        assertEquals(PollAction.NONE, tracker.onPoll(results = false, hash = null))
        assertEquals(PollAction.CAPTURE, tracker.onPoll(results = true, hash = "b"))
    }

    @Test
    fun aNewScreenIsNotBlockedWhenItHasTheSameHashAsAnOldRecordAfterAScreenClear() {
        val tracker = CaptureCycleTracker()
        tracker.onPoll(results = true, hash = "a")
        tracker.onRecorded("a")
        tracker.onPoll(results = false, hash = null)
        assertEquals(PollAction.CAPTURE, tracker.onPoll(results = true, hash = "a"))
    }
}
