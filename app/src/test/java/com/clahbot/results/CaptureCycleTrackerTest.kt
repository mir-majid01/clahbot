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
    fun oneMissedDetectionDoesNotClearARecordedScreen() {
        val tracker = CaptureCycleTracker()
        tracker.onPoll(results = true, hash = "0000000000000000")
        tracker.onRecorded("0000000000000000")
        assertEquals(PollAction.NONE, tracker.onPoll(results = false, hash = "0000000000000000"))
        assertEquals(PollAction.WAIT_FOR_CLEAR, tracker.onPoll(results = true, hash = "ffffffffffffffff"))
    }

    @Test
    fun aSimilarFrameCountsAsTheSameScreenEvenWhenTheAnchorsAreMissed() {
        val tracker = CaptureCycleTracker()
        tracker.onPoll(results = true, hash = "0000000000000000")
        tracker.onRecorded("0000000000000000")
        repeat(5) {
            assertEquals(PollAction.NONE, tracker.onPoll(results = false, hash = "0000000000000001"))
        }
        assertEquals(PollAction.WAIT_FOR_CLEAR, tracker.onPoll(results = true, hash = "ffffffffffffffff"))
    }

    @Test
    fun aGenuinelyDifferentFrameClearsAfterThreePolls() {
        val tracker = CaptureCycleTracker()
        tracker.onPoll(results = true, hash = "0000000000000000")
        tracker.onRecorded("0000000000000000")
        repeat(3) {
            assertEquals(PollAction.NONE, tracker.onPoll(results = false, hash = "ffffffffffffffff"))
        }
        assertEquals(PollAction.CAPTURE, tracker.onPoll(results = true, hash = "ffffffffffffffff"))
    }

    @Test
    fun afterTheScreenClearsForThreePollsANewAttackCanBeCaptured() {
        val tracker = CaptureCycleTracker()
        tracker.onPoll(results = true, hash = "a")
        tracker.onRecorded("a")
        repeat(3) {
            assertEquals(PollAction.NONE, tracker.onPoll(results = false, hash = null))
        }
        assertEquals(PollAction.CAPTURE, tracker.onPoll(results = true, hash = "b"))
    }

    @Test
    fun aNewScreenIsNotBlockedWhenItHasTheSameHashAsAnOldRecordAfterAScreenClear() {
        val tracker = CaptureCycleTracker()
        tracker.onPoll(results = true, hash = "a")
        tracker.onRecorded("a")
        repeat(3) { tracker.onPoll(results = false, hash = null) }
        assertEquals(PollAction.CAPTURE, tracker.onPoll(results = true, hash = "a"))
    }
}
