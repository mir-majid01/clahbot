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

    @Test
    fun aManualWatchMayCaptureAnAlreadyOpenScreen() {
        assertTrue(
            ResultsCapturePolicy.mayAutoRecord(
                detectedResults = true,
                startedOnResults = true,
                manual = true
            )
        )
    }

    @Test
    fun anAlreadyRecordedScreenIsNeverRecordedAgain() {
        assertTrue(ResultsCapturePolicy.mayRecordOpenResult(manual = true, alreadyRecorded = false))
        assertFalse(ResultsCapturePolicy.mayRecordOpenResult(manual = true, alreadyRecorded = true))
        assertFalse(ResultsCapturePolicy.mayRecordOpenResult(manual = false, alreadyRecorded = false))
    }
}
