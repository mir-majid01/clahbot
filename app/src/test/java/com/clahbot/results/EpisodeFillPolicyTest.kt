package com.clahbot.results

import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.ResultsOutcome
import com.clahbot.ocr.ResultsRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeFillPolicyTest {

    private fun record(
        gold: Long? = 2_542,
        elixir: Long? = null,
        dark: Long? = 35_016,
        bonusGold: Long? = 210_000,
        bonusElixir: Long? = 210_000,
        bonusDark: Long? = 1_344,
        bonusState: String? = AttackRecord.BONUS_CAPTURED,
        complete: Boolean = false,
        reviewReason: String? = "Some main loot fields were not captured"
    ) = AttackRecord(
        id = 7L,
        startedAt = 1_000L,
        gold = gold,
        elixir = elixir,
        darkElixir = dark,
        bonusGold = bonusGold,
        bonusElixir = bonusElixir,
        bonusDarkElixir = bonusDark,
        bonusState = bonusState,
        complete = complete,
        reviewReason = reviewReason,
        confirmed = true
    )

    private fun read(
        gold: Long? = 2_541_267,
        elixir: Long? = 1_668_782,
        dark: Long? = 35_016,
        bonusGold: Long? = 210_000,
        bonusElixir: Long? = 210_000,
        bonusDark: Long? = 1_344
    ) = ResultsRead(
        outcome = ResultsOutcome.VICTORY,
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
        bonusPanel = true
    )

    @Test
    fun laterReadsFillOnlyMissingFieldsAndKeepTheRecordId() {
        val filled = EpisodeFillPolicy.fill(record(), read())
        assertEquals(7L, filled.record.id)
        assertEquals(2_542L, filled.record.gold)
        assertEquals(1_668_782L, filled.record.elixir)
        assertEquals(35_016L, filled.record.darkElixir)
        assertTrue(filled.record.complete)
        assertFalse(filled.record.verified)
        assertTrue(filled.conflicts.any { it.contains("Gold changed") })
        assertTrue(filled.record.reviewReason!!.contains("Gold changed"))
    }

    @Test
    fun anUnreadableRecordCanBeFilledFromAFullRead() {
        val filled = EpisodeFillPolicy.fill(
            record(
                gold = null,
                elixir = null,
                dark = null,
                bonusGold = null,
                bonusElixir = null,
                bonusDark = null,
                bonusState = null,
                reviewReason = "Results screen captured; loot not captured"
            ),
            read()
        )
        assertEquals(2_541_267L, filled.record.gold)
        assertEquals(1_668_782L, filled.record.elixir)
        assertEquals(35_016L, filled.record.darkElixir)
        assertTrue(filled.record.complete)
        assertEquals(AttackRecord.BONUS_CAPTURED, filled.record.bonusState)
        assertNull(filled.record.reviewReason)
        assertTrue(filled.changed)
    }

    @Test
    fun aCompleteRecordIsNotChangedByMatchingLaterReads() {
        val complete = record(
            gold = 2_541_267,
            elixir = 1_668_782,
            dark = 35_016,
            complete = true,
            reviewReason = null
        )
        val filled = EpisodeFillPolicy.fill(complete, read())
        assertFalse(filled.changed)
        assertEquals(complete, filled.record)
    }
}
