package com.clahbot.results

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsCapturePolicyTest {

    @Test
    fun aDetectedResultsScreenMayCreateARecord() {
        assertTrue(
            ResultsCapturePolicy.mayAutoRecord(
                detectedResults = true,
                startedOnResults = false
            )
        )
    }

    @Test
    fun aFinishedPlaybackOrStaleResultsScreenMayNot() {
        assertFalse(
            ResultsCapturePolicy.mayAutoRecord(
                detectedResults = false,
                startedOnResults = false
            )
        )
        assertFalse(
            ResultsCapturePolicy.mayAutoRecord(
                detectedResults = true,
                startedOnResults = true
            )
        )
    }
}
