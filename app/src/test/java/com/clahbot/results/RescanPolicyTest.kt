package com.clahbot.results

import com.clahbot.ocr.ResultsOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescanPolicyTest {

    private val original = com.clahbot.data.models.AttackRecord(
        id = 7, startedAt = 1_000, gold = 500_000, elixir = 200_000, darkElixir = 5_000,
        confirmed = true, complete = false, reviewReason = "Loot not settled",
        outcome = "victory", resultsScreenshot = "original.png", note = "Original note",
        bonusState = com.clahbot.data.models.AttackRecord.BONUS_NONE
    )

    private fun read() = com.clahbot.ocr.ResultsRead(
        outcome = ResultsOutcome.VICTORY, stars = null,
        gold = 520_000, elixir = 210_000, darkElixir = null,
        bonusGold = null, bonusElixir = null, bonusDarkElixir = null,
        balanceGold = null, balanceElixir = null, balanceDarkElixir = null,
        confidence = 0.75, needsReview = true,
        issues = listOf("loot row 3: no OCR line in slot", "loot row 3: not captured (unreadable)")
    )

    @Test
    fun mainOnlyRescanPreservesDarkAndOriginalIdentity() {
        val merged = RescanPolicy.merge(original, read())!!
        org.junit.Assert.assertEquals(520_000L, merged.gold)
        org.junit.Assert.assertEquals(5_000L, merged.darkElixir)
        org.junit.Assert.assertEquals(original.id, merged.id)
        org.junit.Assert.assertEquals(original.resultsScreenshot, merged.resultsScreenshot)
        org.junit.Assert.assertEquals(original.note, merged.note)
        assertTrue(merged.complete)
        assertTrue(merged.verified)
        org.junit.Assert.assertNull(merged.reviewReason)
        org.junit.Assert.assertEquals(merged, RescanPolicy.merge(merged, read()))
    }

    @Test
    fun absentBonusPanelCannotEraseKnownMissingBonus() {
        val merged = RescanPolicy.merge(
            original.copy(bonusState = com.clahbot.data.models.AttackRecord.BONUS_NOT_CAPTURED), read()
        )!!
        assertTrue(merged.needsReviewDefinition)
        assertFalse(merged.verified)
        org.junit.Assert.assertEquals(com.clahbot.data.models.AttackRecord.BONUS_NOT_CAPTURED, merged.bonusState)
    }

    @Test
    fun partialBonusDoesNotBecomeCaptured() {
        val merged = RescanPolicy.merge(original, read().copy(bonusPanel = true, bonusGold = 10))!!
        assertFalse(merged.verified)
        assertTrue(merged.needsReviewDefinition)
        org.junit.Assert.assertEquals(10L, merged.bonusGold)
    }

    @Test
    fun aCompleteBonusReadResolvesMissingBonus() {
        val merged = RescanPolicy.merge(
            original.copy(bonusState = com.clahbot.data.models.AttackRecord.BONUS_NOT_CAPTURED),
            read().copy(bonusPanel = true, bonusGold = 10, bonusElixir = 20, bonusDarkElixir = 3)
        )!!
        assertTrue(merged.verified)
        assertFalse(merged.needsReviewDefinition)
        org.junit.Assert.assertEquals(com.clahbot.data.models.AttackRecord.BONUS_CAPTURED, merged.bonusState)
    }

    @Test
    fun conflictingOcrDoesNotClearReview() {
        val merged = RescanPolicy.merge(original, read().copy(issues = listOf("loot row 1: conflict")))!!
        assertFalse(merged.verified)
        assertTrue(merged.reviewReason!!.contains("conflict"))
    }

    @Test
    fun mismatchedOutcomeDoesNotChangeTheOriginalRecord() {
        org.junit.Assert.assertNull(RescanPolicy.merge(original, read().copy(outcome = ResultsOutcome.DEFEAT)))
    }

    @Test
    fun implausibleOptionalFieldRetainsReviewInsteadOfBecomingVerified() {
        val merged = RescanPolicy.merge(original, read().copy(darkElixir = -1))!!
        org.junit.Assert.assertEquals(original.darkElixir, merged.darkElixir)
        assertFalse(merged.verified)
        assertTrue(merged.reviewReason!!.contains("Dark elixir"))
    }

    @Test
    fun acceptsACompleteThreeFieldRead() {
        assertTrue(RescanPolicy.mayApply(ResultsOutcome.VICTORY, 1, 2, 3))
    }

    @Test
    fun rejectsAMissingField() {
        assertFalse(RescanPolicy.mayApply(ResultsOutcome.VICTORY, 1, null, 3))
        assertFalse(RescanPolicy.mayApply(ResultsOutcome.VICTORY, null, 2, 3))
        assertFalse(RescanPolicy.mayApply(null, 1, 2, 3))
    }

    @Test
    fun acceptsMainLootWithoutInventingOptionalDarkElixir() {
        assertTrue(RescanPolicy.mayApply(ResultsOutcome.VICTORY, 1, 2, null))
    }

    @Test
    fun rejectsNegativeOrImplausibleLoot() {
        assertFalse(RescanPolicy.mayApply(ResultsOutcome.VICTORY, -1, 2, 3))
        assertFalse(RescanPolicy.mayApply(ResultsOutcome.VICTORY, 1, 20_000_000, 3))
    }

    @Test
    fun missingBonusAloneIsARescanCandidate() {
        val record = com.clahbot.data.models.AttackRecord(
            id = 1, confirmed = true, complete = true, gold = 10, elixir = 20,
            bonusState = com.clahbot.data.models.AttackRecord.BONUS_NOT_CAPTURED,
            resultsScreenshot = "saved.png"
        )
        org.junit.Assert.assertEquals(listOf(record), ResultsRescan.candidates(listOf(record)))
    }
}
