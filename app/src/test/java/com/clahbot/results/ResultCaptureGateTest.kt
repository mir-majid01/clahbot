package com.clahbot.results

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultCaptureGateTest {

    @Test
    fun aFreshNegativeSampleDoesNotHold() = runBlocking {
        val gate = ResultCaptureGate(
            freshEnoughMs = 500,
            freshSampleWaitMs = 200,
            holdTimeoutMs = 200,
            stepMs = 5
        )
        val poll = launch {
            delay(20)
            gate.notePoll(System.currentTimeMillis(), false, "no anchor")
        }
        val engaged = gate.awaitClearance()
        poll.join()
        assertFalse(engaged)
    }

    @Test
    fun aPositiveSampleWaitsUntilTheCaptureReleasesIt() = runBlocking {
        val gate = ResultCaptureGate(
            freshEnoughMs = 500,
            freshSampleWaitMs = 200,
            holdTimeoutMs = 1_000,
            stepMs = 5
        )
        val started = System.currentTimeMillis()
        val capture = launch {
            delay(20)
            gate.requestHold()
            gate.notePoll(System.currentTimeMillis(), true, null)
            delay(120)
            gate.releaseHold()
        }
        val engaged = gate.awaitClearance()
        val elapsed = System.currentTimeMillis() - started
        capture.join()
        assertTrue(engaged)
        assertTrue("waited for the hold, elapsed=$elapsed", elapsed >= 100)
    }

    @Test
    fun aHoldThatIsNeverReleasedTimesOut() = runBlocking {
        val gate = ResultCaptureGate(
            freshEnoughMs = 500,
            freshSampleWaitMs = 200,
            holdTimeoutMs = 150,
            stepMs = 5
        )
        val started = System.currentTimeMillis()
        val capture = launch {
            delay(20)
            gate.requestHold()
            gate.notePoll(System.currentTimeMillis(), true, null)
        }
        val engaged = gate.awaitClearance()
        val elapsed = System.currentTimeMillis() - started
        capture.join()
        assertTrue(engaged)
        assertTrue("timed out after the hold, elapsed=$elapsed", elapsed >= 120)
    }
}
