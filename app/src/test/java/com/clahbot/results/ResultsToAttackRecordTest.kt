package com.clahbot.results

import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.ResultsOutcome
import com.clahbot.ocr.ResultsRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsToAttackRecordTest {

    private val session = CaptureSession(
        id = 42L,
        startedAt = 1_000L,
        macroId = 7L,
        macroName = "Attack",
        speed = 1.5f,
        repeat = "3"
    )

    private fun read(
        outcome: ResultsOutcome = ResultsOutcome.VICTORY,
        gold: Long? = 503_859,
        elixir: Long? = 568_045,
        dark: Long? = 5_713,
        bonusGold: Long? = 370_000,
        bonusElixir: Long? = 370_000,
        bonusDark: Long? = 2_750
    ) = ResultsRead(
        outcome = outcome,
        stars = null,
        gold = gold,
        elixir = elixir,
        darkElixir = dark,
        bonusGold = bonusGold,
        bonusElixir = bonusElixir,
        bonusDarkElixir = bonusDark,
        balanceGold = 17_456_885,
        balanceElixir = 17_944_607,
        balanceDarkElixir = 272_302,
        confidence = 1.0,
        needsReview = false,
        issues = emptyList(),
        bonusPanel = bonusGold != null || bonusElixir != null || bonusDark != null
    )

    @Test
    fun detectedResultsCreateOneConfirmedOcrRecord() {
        val record = ResultsToAttackRecord.build(
            session = session,
            read = read(),
            playbackEndedAt = 61_000L,
            interrupted = false,
            screenshotPath = "/tmp/screen.png",
            sidecarPath = "/tmp/screen.json"
        )

        assertEquals(42L, record.id)
        assertEquals(1_000L, record.startedAt)
        assertEquals(61_000L, record.endedAt)
        assertEquals(60_000L, record.durationMs)
        assertEquals(7L, record.macroId)
        assertEquals("Attack", record.macroName)
        assertEquals(1.5f, record.speedMultiplier)
        assertEquals("3", record.repeatMode)
        assertEquals(503_859L, record.gold)
        assertEquals(568_045L, record.elixir)
        assertEquals(5_713L, record.darkElixir)
        assertEquals(370_000L, record.bonusGold)
        assertEquals(370_000L, record.bonusElixir)
        assertEquals(2_750L, record.bonusDarkElixir)
        assertEquals(AttackRecord.BONUS_CAPTURED, record.bonusState)
        assertNull(record.reviewReason)
        assertEquals(AttackRecord.LOOT_OCR, record.lootSource)
        assertEquals(AttackRecord.RESULT_COMPLETED, record.result)
        assertEquals("victory", record.outcome)
        assertEquals("/tmp/screen.png", record.resultsScreenshot)
        assertEquals("/tmp/screen.json", record.resultsSidecar)
        assertTrue(record.verified)
        assertTrue(record.complete)
        assertTrue(record.confirmed)
    }

    @Test
    fun aFailedLootReadStaysNullAndNeverZero() {
        val record = ResultsToAttackRecord.build(
            session = session,
            read = read(
                gold = null,
                elixir = null,
                dark = null,
                bonusGold = null,
                bonusElixir = null,
                bonusDark = null
            ),
            playbackEndedAt = 61_000L,
            interrupted = false,
            screenshotPath = null,
            sidecarPath = null
        )

        assertNull(record.gold)
        assertNull(record.elixir)
        assertNull(record.darkElixir)
        assertFalse(record.hasLoot)
        assertEquals(AttackRecord.LOOT_NONE, record.lootSource)
        assertFalse(record.verified)
        assertFalse(record.complete)
        assertTrue(record.confirmed)
        assertTrue(record.note.contains("loot not captured"))
    }

    @Test
    fun partialLootKeepsMissingFieldsNull() {
        val record = ResultsToAttackRecord.build(
            session = session,
            read = read(elixir = null),
            playbackEndedAt = 61_000L,
            interrupted = true,
            screenshotPath = null,
            sidecarPath = null
        )

        assertEquals(503_859L, record.gold)
        assertNull(record.elixir)
        assertEquals(5_713L, record.darkElixir)
        assertEquals(AttackRecord.LOOT_OCR, record.lootSource)
        assertFalse(record.verified)
        assertTrue(record.needsReview)
        assertTrue(record.reviewReason!!.contains("not captured"))
        assertTrue(record.note.contains("Needs review"))
        assertTrue(record.note.contains("stopped before the macro ended"))
    }

    @Test
    fun eachLaterResultInTheSameWatchGetsItsOwnIdAndStart() {
        val record = ResultsToAttackRecord.build(
            session = session,
            read = read(),
            playbackEndedAt = 90_000L,
            interrupted = false,
            screenshotPath = null,
            sidecarPath = null,
            recordId = 2_000L,
            recordStartedAt = 61_000L,
            captureSource = AttackRecord.SOURCE_AUTO,
            screenHash = "0123456789abcdef"
        )

        assertEquals(2_000L, record.id)
        assertEquals(61_000L, record.startedAt)
        assertEquals(29_000L, record.durationMs)
        assertEquals(AttackRecord.SOURCE_AUTO, record.captureSource)
        assertEquals("0123456789abcdef", record.screenHash)
    }

    @Test
    fun anUnreadableScreenStillCreatesOneConfirmedRecordWithNullLoot() {
        val record = ResultsToAttackRecord.buildUnreadable(
            session = session,
            playbackEndedAt = 61_000L,
            interrupted = false,
            screenshotPath = "/tmp/unreadable.png",
            sidecarPath = "/tmp/unreadable.json",
            recordId = 2_000L,
            recordStartedAt = 1_000L,
            screenHash = "0123456789abcdef"
        )

        assertEquals(2_000L, record.id)
        assertEquals(1_000L, record.startedAt)
        assertNull(record.gold)
        assertNull(record.elixir)
        assertNull(record.darkElixir)
        assertFalse(record.hasLoot)
        assertEquals(AttackRecord.LOOT_NONE, record.lootSource)
        assertEquals(AttackRecord.SOURCE_AUTO, record.captureSource)
        assertEquals("0123456789abcdef", record.screenHash)
        assertFalse(record.verified)
        assertFalse(record.complete)
        assertTrue(record.confirmed)
        assertTrue(record.note.contains("loot not captured"))
    }
}
