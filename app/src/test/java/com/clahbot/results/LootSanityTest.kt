package com.clahbot.results

import com.clahbot.ocr.ResultsOutcome
import com.clahbot.ocr.ResultsRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LootSanityTest {

    private fun read(
        gold: Long? = 1_000_000,
        elixir: Long? = 1_000_000,
        dark: Long? = 10_000,
        bonusGold: Long? = null,
        bonusElixir: Long? = null,
        bonusDark: Long? = null
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
        issues = emptyList()
    )

    @Test
    fun anImpossibleGoldValueIsRemovedAndExplained() {
        val sanitized = LootSanity.sanitize(read(gold = 25_412_267))
        assertNull(sanitized.read.gold)
        assertEquals(1_000_000L, sanitized.read.elixir)
        assertTrue(sanitized.reasons.single().contains("Gold 25412267"))
    }

    @Test
    fun theRound1120000GoldAndTheBonusTestValuesPass() {
        val sanitized = LootSanity.sanitize(
            read(
                gold = 1_120_000,
                elixir = 1_339_872,
                dark = 15_573
            )
        )
        assertTrue(sanitized.reasons.isEmpty())
        assertEquals(1_120_000L, sanitized.read.gold)

        val bonus = LootSanity.sanitize(
            read(
                gold = 51_824,
                elixir = 373_643,
                dark = 1_731,
                bonusGold = 200_000,
                bonusElixir = 200_000,
                bonusDark = 1_280
            )
        )
        assertTrue(bonus.reasons.isEmpty())
        assertEquals(200_000L, bonus.read.bonusGold)
        assertEquals(1_280L, bonus.read.bonusDarkElixir)
    }

    @Test
    fun anImpossibleBonusIsRemoved() {
        val sanitized = LootSanity.sanitize(read(bonusGold = 2_000_000))
        assertNull(sanitized.read.bonusGold)
        assertTrue(sanitized.reasons.single().contains("Bonus gold"))
    }

    @Test
    fun aBalanceDeltaFarBelowTheReportedLootIsFlagged() {
        val read = read(gold = 25_000_000, elixir = 1_600_000, dark = 35_016)
        val reasons = LootSanity.balanceReasons(
            read = read,
            previous = LootSanity.Balances(10_000_000, 10_000_000, 300_000),
            current = LootSanity.Balances(12_500_000, 12_000_000, 350_000)
        )
        assertTrue(reasons.any { it.startsWith("Gold loot") })
        assertTrue(reasons.none { it.startsWith("Elixir loot") })
        assertTrue(reasons.none { it.startsWith("Dark elixir loot") })
    }

    @Test
    fun aConsistentBalanceDeltaPasses() {
        val reasons = LootSanity.balanceReasons(
            read = read(gold = 2_500_000, elixir = 1_600_000, dark = 35_016),
            previous = LootSanity.Balances(10_000_000, 10_000_000, 300_000),
            current = LootSanity.Balances(12_600_000, 11_700_000, 350_000)
        )
        assertTrue(reasons.isEmpty())
    }
}
