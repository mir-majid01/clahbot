package com.clahbot.results

import com.clahbot.ocr.ResultsOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescanPolicyTest {

    @Test
    fun acceptsACompleteThreeFieldRead() {
        assertTrue(RescanPolicy.mayApply(ResultsOutcome.VICTORY, 1, 2, 3))
    }

    @Test
    fun rejectsAMissingField() {
        assertFalse(RescanPolicy.mayApply(ResultsOutcome.VICTORY, 1, null, 3))
        assertFalse(RescanPolicy.mayApply(ResultsOutcome.VICTORY, null, 2, 3))
        assertFalse(RescanPolicy.mayApply(ResultsOutcome.VICTORY, 1, 2, null))
        assertFalse(RescanPolicy.mayApply(null, 1, 2, 3))
    }
}
