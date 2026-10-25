package com.clahbot.ocrlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberParserTest {

    @Test
    fun parsesSpaceSeparatedNumbers() {
        assertEquals(1_200_960L, NumberParser.parse("1 200 960"))
        assertEquals(1_200_960L, NumberParser.parse("1\u00A0200\u00A0960"))
        assertEquals(1_200_960L, NumberParser.parse("1,200,960"))
        assertEquals(310_000L, NumberParser.parse("+310000"))
    }

    @Test
    fun keepsARealZeroButRejectsFailedReads() {
        assertEquals(0L, NumberParser.parse("0"))
        assertNull(NumberParser.parse("not a number"))
        assertNull(NumberParser.parse(""))
        assertNull(NumberParser.parseLoot("70"))
        assertEquals(300L, NumberParser.parseLoot("300"))
    }

    @Test
    fun readsPercentages() {
        assertEquals(70, NumberParser.percent("70%"))
        assertEquals(70, NumberParser.percent("70 %"))
        assertEquals(70, NumberParser.percent("Total damage: 70%"))
        assertEquals(100, NumberParser.percent("100% Bonus:"))
        assertNull(NumberParser.percent("no percent"))
        assertNull(NumberParser.percent("101%"))
    }
}
