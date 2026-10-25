package com.clahbot.data

import com.clahbot.data.models.AttackRecordPolicy
import com.clahbot.data.models.AttackTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttackRecordPolicyTest {

    @Test
    fun playbackCompletionCannotCreateAnAttackRecord() {
        assertFalse(
            AttackRecordPolicy.mayCreateRecord(AttackTrigger.PLAYBACK_COMPLETED)
        )
    }

    @Test
    fun onlyADetectedResultsScreenMayCreateAnAttackRecord() {
        assertTrue(
            AttackRecordPolicy.mayCreateRecord(AttackTrigger.DETECTED_RESULTS_SCREEN)
        )
    }
}
