package com.clahbot.ocrlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberParserTest {

    @Test
    fun parsesGroupedLootAndBalances() {
        assertEquals(1_200_960L, NumberParser.parseGrouped("1 200 960"))
        assertEquals(1_200_960L, NumberParser.parseGrouped("1\u00A0200\u00A0960"))
        assertEquals(1_200_960L, NumberParser.parseGrouped("1,200,960"))
        assertEquals(417_994L, NumberParser.parseGrouped("417 994"))
        assertEquals(3_007L, NumberParser.parseGrouped("3 007"))
        assertEquals(300L, NumberParser.parseGrouped("300"))
    }

    @Test
    fun correctsKnownOcrLetterConfusionsOnlyInsideValidNumbers() {
        assertEquals(503_859L, NumberParser.parseGrouped("S03 859"))
        assertEquals(568_045L, NumberParser.parseGrouped("S68 04S"))
        assertEquals(16_584_626L, NumberParser.parseGrouped("16 584 G26"))
        assertEquals(17_006_562L, NumberParser.parseGrouped("17 O06 S62"))
    }

    @Test
    fun ungroupedLootIsRejectedEvenWhenTheDigitsLookRight() {
        assertNull(NumberParser.parseGrouped("4959"))
        assertNull(NumberParser.parseGrouped("37000o"))
        assertNull(NumberParser.parseGrouped("+310000"))
        assertNull(NumberParser.parseGrouped("370 000 0"))
        assertNull(NumberParser.parseGrouped("not a number"))
        assertNull(NumberParser.parseGrouped("You got:GYCB"))
        assertNull(NumberParser.parseGrouped("SoUR ElixiR 300"))
    }

    @Test
    fun bonusUsesItsOwnDisplayFormat() {
        assertEquals(310_000L, NumberParser.parseBonus("+310000"))
        assertEquals(370_000L, NumberParser.parseBonus("37000o"))
        assertEquals(370_000L, NumberParser.parseBonus("37000O"))
        assertEquals(2_750L, NumberParser.parseBonus("+2750"))
        assertEquals(2_100L, NumberParser.parseBonus("•2100"))
        assertEquals(37_000L, NumberParser.parseBonus("37000"))
        assertNull(NumberParser.parseBonus("+"))
        assertNull(NumberParser.parseBonus("100% BONUS:"))
        assertNull(NumberParser.parseBonus("12345678"))
    }

    @Test
    fun rowCandidatesCanBeFoundWithoutRequiringFinalFormatting() {
        assertTrue(NumberParser.isNumericLike("4959"))
        assertTrue(NumberParser.isNumericLike("S03 859"))
        assertTrue(NumberParser.isNumericLike("•370000", allowBonusPrefix = true))
        assertFalse(NumberParser.isNumericLike("70"))
        assertFalse(NumberParser.isNumericLike("Troops expended:"))
        assertFalse(NumberParser.isNumericLike("You got:GYCB"))
    }
}
