package com.clahbot.ocrlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalanceMathTest {

    private fun read(
        outcome: ResultsOutcome,
        gold: Long?,
        elixir: Long?,
        dark: Long?,
        lootGold: Long? = null,
        lootElixir: Long? = null,
        lootDark: Long? = null,
        bonusGold: Long? = null,
        bonusElixir: Long? = null,
        bonusDark: Long? = null
    ): ResultsRead = ResultsRead(
        outcome = outcome,
        stars = null,
        gold = lootGold,
        elixir = lootElixir,
        darkElixir = lootDark,
        bonusGold = bonusGold,
        bonusElixir = bonusElixir,
        bonusDarkElixir = bonusDark,
        balanceGold = gold,
        balanceElixir = elixir,
        balanceDarkElixir = dark,
        confidence = 1.0,
        needsReview = false,
        issues = emptyList()
    )

    private val defeat = read(
        ResultsOutcome.DEFEAT,
        gold = 16_584_626,
        elixir = 17_006_562,
        dark = 263_839,
        lootGold = 363_609,
        lootElixir = 241_528,
        lootDark = 4_959
    )

    private val victory = read(
        ResultsOutcome.VICTORY,
        gold = 17_456_885,
        elixir = 17_944_607,
        dark = 272_302,
        lootGold = 503_859,
        lootElixir = 568_045,
        lootDark = 5_713,
        bonusGold = 370_000,
        bonusElixir = 370_000,
        bonusDark = 2_750
    )

    @Test
    fun exactIdentityPredictsTheNextLoot() {
        val expected = requireNotNull(BalanceMath.expectedLoot(defeat, victory, 1_600))
        assertEquals(503_859L, expected.gold)
        assertEquals(568_045L, expected.elixir)
        assertEquals(5_713L, expected.dark)
        assertEquals("pass", BalanceMath.crossCheck(defeat, victory, 1_600))
    }

    @Test
    fun aWrongValueFailsTheCrossCheck() {
        val wrongLoot = victory.copy(gold = 603_859)
        assertEquals("mismatch", BalanceMath.crossCheck(defeat, wrongLoot, 1_600))
    }

    @Test
    fun identityOnlyAppliesBetweenDefeatAndVictory() {
        assertNull(BalanceMath.expectedLoot(victory, victory, 1_600))
        assertEquals("not_applicable", BalanceMath.crossCheck(victory, victory, 1_600))
        assertNull(BalanceMath.expectedLoot(null, victory, 1_600))
        assertEquals("not_available", BalanceMath.crossCheck(null, victory, 1_600))
    }

    @Test
    fun identityDoesNotApplyWithoutSearchCost() {
        assertNull(BalanceMath.expectedLoot(defeat, victory, null))
        assertEquals("not_available", BalanceMath.crossCheck(defeat, victory, null))
    }

    @Test
    fun differentAccountProducesNoPlausibleLoot() {
        val otherAccount = read(
            ResultsOutcome.VICTORY,
            gold = 4_251_421,
            elixir = 4_944_626,
            dark = 256_390,
            lootGold = 882_432,
            lootElixir = 1_200_960,
            lootDark = 1_162,
            bonusGold = 310_000,
            bonusElixir = 310_000,
            bonusDark = 2_100
        )
        assertNull(BalanceMath.expectedLoot(victory, otherAccount, 1_600))
    }
}
