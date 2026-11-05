package com.clahbot.results

import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.ResultsOutcome
import com.clahbot.ocr.ResultsRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePolicyTest {

    private fun read(
        gold: Long? = 503_859,
        elixir: Long? = 568_045,
        dark: Long? = 5_713,
        bonusGold: Long? = 370_000,
        bonusElixir: Long? = 370_000,
        bonusDark: Long? = 2_750,
        panel: Boolean = true,
        outcome: ResultsOutcome = ResultsOutcome.VICTORY
    ) = ResultsRead(
        outcome = outcome,
        stars = null,
        gold = gold,
        elixir = elixir,
        darkElixir = dark,
        bonusGold = bonusGold,
        bonusElixir = bonusElixir,
        bonusDarkElixir = bonusDark,
        balanceGold = null,
        balanceElixir = null,
        balanceDarkElixir = null,
        confidence = 1.0,
        needsReview = false,
        issues = emptyList(),
        bonusPanel = panel
    )

    private fun boxes(offset: Int = 0) = listOf(
        OcrBox(100, 200 + offset, 300, 240 + offset),
        OcrBox(100, 250 + offset, 300, 290 + offset),
        OcrBox(100, 300 + offset, 300, 340 + offset)
    )

    private fun frame(
        at: Long,
        read: ResultsRead?,
        rows: Int = if (read?.bonusPanel == true) 3 else 0,
        offset: Int = 0
    ) = EpisodeFrame(
        atMs = at,
        resultsScreen = read != null,
        read = read,
        bonusRowBoxes = if (rows > 0) boxes(offset) else emptyList(),
        bonusRowCount = rows
    )

    private fun finish(decision: EpisodeDecision): EpisodeResult {
        assertTrue("expected a finish, got $decision", decision is EpisodeDecision.Finish)
        return (decision as EpisodeDecision.Finish).result
    }

    private fun run(vararg frames: EpisodeFrame, policy: EpisodePolicy = EpisodePolicy(0L)): EpisodeResult {
        var decision: EpisodeDecision = EpisodeDecision.Continue
        for (frame in frames) {
            decision = policy.onFrame(frame)
            if (decision is EpisodeDecision.Finish) return decision.result
        }
        return policy.finishNow(frames.lastOrNull()?.atMs ?: 0L)
    }

    @Test
    fun completeMainAndThreeStableBonusFramesNeedNoReview() {
        val result = run(
            frame(0, read(), offset = 0),
            frame(250, read(), offset = 0),
            frame(500, read(), offset = 0),
            frame(750, read(), offset = 0)
        )
        assertTrue(result.mainStable)
        assertTrue(result.bonusResolved)
        assertTrue(result.reasons.isEmpty())
        assertEquals(370_000L, result.bonusGold)
        assertEquals(AttackRecord.BONUS_CAPTURED, result.bonusState)
    }

    @Test
    fun aMovingBonusPanelIsNotAcceptedEvenWhenTheValuesMatch() {
        val policy = EpisodePolicy(0L)
        assertTrue(
            policy.onFrame(frame(0, read(), offset = 0)) is EpisodeDecision.Continue
        )
        assertTrue(
            policy.onFrame(frame(250, read(), offset = 12)) is EpisodeDecision.Continue
        )
        assertTrue(
            policy.onFrame(frame(500, read(), offset = 0)) is EpisodeDecision.Continue
        )
        // Values have been equal three times, but the panel moved every frame.
        val result = policy.finishNow(12_000L)
        assertEquals(AttackRecord.BONUS_NOT_CAPTURED, result.bonusState)
        assertNull(result.bonusGold)
        assertTrue(result.reasons.any { it.contains("Bonus missing") || it.contains("Bonus unreadable") })
    }

    @Test
    fun aWrongBonusDigitRepeatedThreeTimesIsNeverWritten() {
        val bad = read(bonusGold = 2_697_000)
        val result = run(
            frame(0, bad),
            frame(250, bad),
            frame(500, bad),
            frame(750, bad),
            frame(12_000, bad)
        )
        assertNull(result.bonusGold)
        assertEquals(AttackRecord.BONUS_NOT_CAPTURED, result.bonusState)
        assertTrue(result.reasons.any { it.contains("Bonus unreadable") })
        assertEquals(503_859L, result.gold)
    }

    @Test
    fun noBonusPanelForFourSecondsIsRecordedAsAbsentWithoutReview() {
        val noPanel = read(bonusGold = null, bonusElixir = null, bonusDark = null, panel = false)
        val result = run(
            frame(0, noPanel),
            frame(250, noPanel),
            frame(3_000, noPanel),
            frame(4_300, noPanel)
        )
        assertEquals(AttackRecord.BONUS_NONE, result.bonusState)
        assertTrue(result.bonusResolved)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun aDefeatNeedsNoFourSecondBonusWait() {
        val defeat = read(
            panel = false,
            bonusGold = null,
            bonusElixir = null,
            bonusDark = null,
            outcome = ResultsOutcome.DEFEAT
        )
        val result = run(frame(0, defeat), frame(250, defeat))
        assertTrue(result.mainStable)
        assertEquals(AttackRecord.BONUS_NONE, result.bonusState)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun aClosedEarlyScreenKeepsConfirmedValuesAndSaysSo() {
        val policy = EpisodePolicy(0L)
        policy.onFrame(frame(0, read(panel = false, bonusGold = null, bonusElixir = null, bonusDark = null)))
        val decision = policy.onMissing(1_600L)
        val result = finish(decision)
        assertTrue(result.closedEarly)
        assertEquals(503_859L, result.gold)
        assertFalse(result.mainStable)
        assertTrue(result.reasons.any { it.contains("Closed early") })
        assertTrue(result.reasons.any { it.contains("Loot not settled") })
    }

    @Test
    fun aClosedEarlyScreenAfterEverythingSettledNeedsNoReview() {
        val policy = EpisodePolicy(0L)
        policy.onFrame(frame(0, read()))
        policy.onFrame(frame(250, read()))
        policy.onFrame(frame(500, read()))
        policy.onFrame(frame(750, read()))
        val result = finish(policy.onMissing(2_400L))
        assertTrue(result.closedEarly)
        assertTrue(result.bonusResolved)
        assertTrue(result.reasons.isEmpty())
        assertTrue(result.hasMain)
    }

    @Test
    fun aScreenThatNeverProducesRowsIsOneReviewedRecord() {
        val policy = EpisodePolicy(0L)
        assertTrue(policy.onFrame(frame(0, null, rows = 0)) is EpisodeDecision.Continue)
        val result = finish(policy.onMissing(1_600L))
        assertNull(result.gold)
        assertTrue(result.reasons.any { it.contains("Loot unreadable") })
        assertTrue(result.reasons.any { it.contains("Closed early") })
    }

    @Test
    fun changingMainValuesAreMarkedAsNotSettled() {
        val policy = EpisodePolicy(0L)
        policy.onFrame(frame(0, read(gold = 1_000)))
        policy.onFrame(frame(250, read(gold = 1_100)))
        policy.onFrame(frame(500, read(gold = 1_200)))
        val result = policy.finishNow(12_000L)
        assertFalse(result.mainStable)
        assertTrue(result.reasons.any { it.contains("Loot not settled") })
    }

    @Test
    fun aMissingDarkRowIsNamedButDoesNotBlockTheRecord() {
        val noDark = read(dark = null)
        // Gold and elixir agreed twice, so the reading is stable: the record is
        // saved about a second after the screen appears instead of waiting out
        // the hard limit for a row that is often simply not rendered.
        val result = run(frame(0, noDark), frame(600, noDark))
        assertNull(result.darkElixir)
        assertTrue(result.hasMain)
        assertTrue(result.mainStable)
        assertTrue(result.partialReasons.any { it == "Dark elixir unreadable" })
        assertFalse(result.reasons.any { it == "Dark elixir unreadable" })
    }

    @Test
    fun aMissingGoldRowStillNeedsReview() {
        val result = run(frame(0, read(gold = null)), frame(600, read(gold = null)))
        assertFalse(result.hasMain)
        assertFalse(result.mainStable)
        assertTrue(result.reasons.any { it == "Gold unreadable" })
    }

    @Test
    fun theHardLimitEndsAnUnsettledBonusPanel() {
        val policy = EpisodePolicy(0L)
        var decision: EpisodeDecision = policy.onFrame(frame(0, read(bonusGold = null)))
        var at = 0L
        while (decision is EpisodeDecision.Continue && at < 20_000L) {
            at += 500L
            decision = policy.onFrame(frame(at, read(bonusGold = null)))
        }
        val result = finish(decision)
        assertEquals(AttackRecord.BONUS_NOT_CAPTURED, result.bonusState)
        assertTrue(result.reasons.any { it.contains("Bonus missing") || it.contains("Bonus unreadable") })
    }
}
