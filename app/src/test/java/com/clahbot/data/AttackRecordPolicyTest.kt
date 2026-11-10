package com.clahbot.data

import com.clahbot.data.models.AttackRecordPolicy
import com.clahbot.data.models.AttackTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttackRecordPolicyTest {

    private val original = com.clahbot.data.models.AttackRecord(
        id = 1, confirmed = true, gold = 500_000, elixir = 200_000,
        bonusState = com.clahbot.data.models.AttackRecord.BONUS_NOT_CAPTURED,
        reviewReason = "Bonus missing"
    )

    @Test
    fun emptyBonusFieldsDoNotMeanNoBonus() {
        val edited = AttackRecordPolicy.correctLoot(original, 520_000, 210_000, null, null, null, null, false)
        org.junit.Assert.assertEquals(520_000L, edited.gold)
        assertTrue(edited.needsReviewDefinition)
        assertFalse(edited.verified)
        org.junit.Assert.assertEquals(com.clahbot.data.models.AttackRecord.BONUS_NOT_CAPTURED, edited.bonusState)
    }

    @Test
    fun explicitNoBonusResolvesTheMissingBonusFlag() {
        val edited = AttackRecordPolicy.correctLoot(original, 520_000, 210_000, null, null, null, null, true)
        assertTrue(edited.verified)
        assertFalse(edited.needsReviewDefinition)
        org.junit.Assert.assertEquals(com.clahbot.data.models.AttackRecord.BONUS_NONE, edited.bonusState)
    }

    @Test
    fun oneMainFieldCannotBecomeVerified() {
        val edited = AttackRecordPolicy.correctLoot(original, 520_000, null, null, null, null, null, true)
        assertFalse(edited.complete)
        assertFalse(edited.verified)
        assertTrue(edited.reviewReason!!.contains("elixir"))
    }

    @Test
    fun partialBonusStillNeedsReview() {
        val edited = AttackRecordPolicy.correctLoot(original, 520_000, 210_000, null, 10, null, null, false)
        assertFalse(edited.verified)
        org.junit.Assert.assertEquals(10L, edited.bonusGold)
        assertTrue(edited.needsReviewDefinition)
    }

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
