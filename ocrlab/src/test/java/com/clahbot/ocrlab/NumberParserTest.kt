package com.clahbot.ocrlab

import com.clahbot.ocr.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberParserTest {

    private fun values(raw: String, bonus: Boolean = false): Set<Long> =
        if (bonus) {
            NumberParser.bonusCandidates(raw).map { it.value }.toSet()
        } else {
            NumberParser.lootCandidates(raw).map { it.value }.toSet()
        }

    @Test
    fun acceptsSoftGroupingAndMissingSpaces() {
        assertEquals(setOf(1_200_960L), values("1 200 960"))
        assertEquals(setOf(1_200_960L), values("1200 960"))
        assertEquals(setOf(200_960L), values("200 960"))
        assertEquals(setOf(1_200_960L), values("1,200,960"))
        assertEquals(setOf(1_200_960L), values("1\u00A0200\u00A0960"))
        assertEquals(setOf(417_994L), values("417 994"))
        assertEquals(setOf(3_007L), values("3 007"))
        assertEquals(setOf(300L), values("300"))
        assertEquals(setOf(3_700_000L), values("370 000 0"))
    }

    @Test
    fun expandsConfusableLettersToCandidateDigits() {
        assertEquals(setOf(503_859L), values("S03 859"))
        assertEquals(setOf(568_045L), values("S68 04S"))
        assertEquals(setOf(16_584_626L), values("16 584 G26"))
        assertEquals(setOf(17_006_562L), values("17 O06 S62"))
        assertEquals(setOf(5_713L), values("S 713"))
        assertEquals(setOf(256_390L), values("256 39O"))
        assertEquals(setOf(6_038_590L), values("G03 8590"))
        assertEquals(setOf(370_000L), values("37000o"))
        assertEquals(setOf(370_000L), values("37000O"))
        assertEquals(setOf(310_000L), values("+310000"))
        assertEquals(setOf(2_100L), values("•2100"))
    }

    @Test
    fun ambiguousLetterProducesBothCandidatesAndNoSingleValue() {
        assertEquals(setOf(370_006L, 370_008L), values("37000b", bonus = true))
        assertNull(NumberParser.parseBonus("37000b"))
    }

    @Test
    fun ignoresStrayOcrLettersAndLeadingZeros() {
        assertEquals(setOf(5_713L), values("YY5713"))
        assertEquals(setOf(1_162L), values("#1162"))
        assertEquals(setOf(241_528L), values("0241528"))
        assertEquals(setOf(241_528L), values("241528"))
        assertEquals(setOf(882_432L), values("882432"))
    }

    @Test
    fun keepsGroupedDigitsTogetherButSeparatesLabels() {
        assertEquals(setOf(300L), values("Sour Elixir 300"))
        assertEquals(setOf(1_200_960L), values("You got: 1200 960"))
        assertEquals(setOf(417_994L), values("Gold 417 994"))
    }

    @Test
    fun rejectsTooFewDigitsAndPureLabelText() {
        assertEquals(emptySet<Long>(), values("70"))
        assertEquals(emptySet<Long>(), values("8/14"))
        assertEquals(emptySet<Long>(), values("14h 12m"))
        assertEquals(emptySet<Long>(), values("Troops expended:"))
        assertEquals(emptySet<Long>(), values("You got:GYCB"))
        assertEquals(emptySet<Long>(), values("VicoRY"))
        assertEquals(emptySet<Long>(), values("Return Home"))
        assertNull(NumberParser.parseLoot("70"))
        assertNull(NumberParser.parseBonus("+"))
    }

    @Test
    fun bonusUsesItsOwnLengthRange() {
        assertEquals(310_000L, NumberParser.parseBonus("+310000"))
        assertEquals(370_000L, NumberParser.parseBonus("37000o"))
        assertEquals(2_750L, NumberParser.parseBonus("+2750"))
        assertEquals(2_100L, NumberParser.parseBonus("•2100"))
        assertEquals(37_000L, NumberParser.parseBonus("37000"))
        assertNull(NumberParser.parseBonus("123456789"))
    }

    @Test
    fun searchCostIgnoresRankedFractionAndTimes() {
        assertEquals(setOf(1_600L), NumberParser.searchCostCandidates("1600").map { it.value }.toSet())
        assertEquals(setOf(1_600L), NumberParser.searchCostCandidates("Find a Match 1600").map { it.value }.toSet())
        assertEquals(emptySet<Long>(), NumberParser.searchCostCandidates("Find a Match 8/14").map { it.value }.toSet())
        assertEquals(emptySet<Long>(), NumberParser.searchCostCandidates("Time Left: 14h 12m").map { it.value }.toSet())
    }

    @Test
    fun isNumericLikeMatchesCandidatePresence() {
        assertTrue(NumberParser.isNumericLike("4959"))
        assertTrue(NumberParser.isNumericLike("S03 859"))
        assertTrue(NumberParser.isNumericLike("•370000", allowBonusPrefix = true))
        assertFalse(NumberParser.isNumericLike("70"))
        assertFalse(NumberParser.isNumericLike("Troops expended:"))
        assertFalse(NumberParser.isNumericLike("You got:GYCB"))
    }
}
