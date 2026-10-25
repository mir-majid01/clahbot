package com.clahbot.ocrlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsParserTest {

    private fun line(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): OcrLine = OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    private fun victoryPage(): OcrPage = OcrPage(
        width = 2576,
        height = 1159,
        lines = listOf(
            line("Total damage:", 1100, 80, 1400, 140),
            line("70%", 1200, 150, 1300, 210),
            line("VICTORY", 1000, 240, 1300, 310),
            line("You got:", 1050, 380, 1300, 430),
            line("417 994", 1100, 440, 1300, 490),
            line("603 605", 1100, 500, 1300, 550),
            line("3 007", 1100, 560, 1300, 610),
            line("Troops expended:", 1000, 650, 1400, 700),
            line("100% Bonus:", 1550, 430, 1800, 480),
            line("+370000", 1550, 490, 1750, 540),
            line("+370000", 1550, 550, 1750, 600),
            line("+2750", 1550, 610, 1750, 660),
            line("18 389 880", 1700, 30, 1950, 80),
            line("19 074 837", 1700, 90, 1950, 140),
            line("279 811", 1700, 150, 1950, 200)
        )
    )

    private fun defeatPage(): OcrPage = OcrPage(
        width = 2576,
        height = 1159,
        lines = listOf(
            line("Total damage:", 1100, 80, 1400, 140),
            line("35%", 1200, 150, 1300, 210),
            line("Defeat", 1000, 240, 1300, 310),
            line("You got:", 1050, 380, 1300, 430),
            line("363 609", 1100, 440, 1300, 490),
            line("241 528", 1100, 500, 1300, 550),
            line("4 959", 1100, 560, 1300, 610),
            line("Troops expended:", 1000, 650, 1400, 700),
            line("16 584 626", 1700, 30, 1950, 80),
            line("17 006 562", 1700, 90, 1950, 140),
            line("263 839", 1700, 150, 1950, 200)
        )
    )

    private fun sampler(): IconColorSampler = IconColorSampler { box ->
        when (box.top) {
            440, 490 -> ResourceKind.GOLD
            500, 550 -> ResourceKind.ELIXIR
            560, 610 -> ResourceKind.DARK
            else -> null
        }
    }

    @Test
    fun parsesLootBonusAndBalancesByIconColour() {
        val parsed = ResultsScreenParser(sampler()).parse(victoryPage())
        val read = (parsed as? ScreenRead.Results)?.read ?: error("not a results screen")

        assertEquals(ResultsOutcome.VICTORY, read.outcome)
        assertEquals(70, read.damagePercent)
        assertEquals(417_994L, read.gold)
        assertEquals(603_605L, read.elixir)
        assertEquals(3_007L, read.darkElixir)
        assertEquals(370_000L, read.bonusGold)
        assertEquals(370_000L, read.bonusElixir)
        assertEquals(2_750L, read.bonusDarkElixir)
        assertEquals(18_389_880L, read.balanceGold)
        assertEquals(19_074_837L, read.balanceElixir)
        assertEquals(279_811L, read.balanceDarkElixir)
        assertFalse(read.needsReview)
        assertNull(read.stars)
    }

    @Test
    fun assignsByIconColourNotRowOrder() {
        val swapped = IconColorSampler { box ->
            when (box.top) {
                440, 490 -> ResourceKind.ELIXIR
                500, 550 -> ResourceKind.GOLD
                560, 610 -> ResourceKind.DARK
                else -> null
            }
        }
        val read = (ResultsScreenParser(swapped).parse(victoryPage()) as ScreenRead.Results).read

        assertEquals(603_605L, read.gold)
        assertEquals(417_994L, read.elixir)
        assertEquals(3_007L, read.darkElixir)
    }

    @Test
    fun defeatHasLootButNoBonus() {
        val read = (ResultsScreenParser(sampler()).parse(defeatPage()) as ScreenRead.Results).read

        assertEquals(ResultsOutcome.DEFEAT, read.outcome)
        assertEquals(35, read.damagePercent)
        assertEquals(363_609L, read.gold)
        assertEquals(241_528L, read.elixir)
        assertEquals(4_959L, read.darkElixir)
        assertNull(read.bonusGold)
        assertNull(read.bonusElixir)
        assertNull(read.bonusDarkElixir)
    }

    @Test
    fun aScreenWithReturnHomeButNoLootIsNotAResult() {
        val page = OcrPage(
            2576,
            1159,
            listOf(
                line("Searching for opponents", 900, 400, 1700, 470),
                line("Return Home", 200, 900, 500, 980)
            )
        )
        val parsed = ResultsScreenParser(sampler()).parse(page)
        assertTrue(parsed is ScreenRead.Ignored)
    }

    @Test
    fun unknownIconColourLeavesTheFieldUncapturedAndMarksReview() {
        val read = (ResultsScreenParser { null }.parse(victoryPage()) as ScreenRead.Results).read

        assertNull(read.gold)
        assertNull(read.elixir)
        assertNull(read.darkElixir)
        assertTrue(read.needsReview)
        assertTrue(read.issues.any { it.contains("icon colour unknown") })
    }

    @Test
    fun failedLootReadIsNullNotZero() {
        val page = OcrPage(
            2576,
            1159,
            listOf(
                line("Total damage:", 1100, 80, 1400, 140),
                line("70%", 1200, 150, 1300, 210),
                line("VICTORY", 1000, 240, 1300, 310),
                line("You got:", 1050, 380, 1300, 430),
                line("Troops expended:", 1000, 650, 1400, 700)
            )
        )
        val read = (ResultsScreenParser(sampler()).parse(page) as ScreenRead.Results).read
        assertNull(read.gold)
        assertNull(read.elixir)
        assertNull(read.darkElixir)
        assertTrue(read.needsReview)
    }
}
