package com.clahbot.data

import com.clahbot.data.models.LootInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LootInputTest {

    @Test
    fun emptyMeansNotCaptured() {
        assertNull(LootInput.parse(""))
        assertNull(LootInput.parse("   "))
    }

    @Test
    fun zeroIsARealValue() {
        assertEquals(0L, LootInput.parse("0"))
    }

    @Test
    fun commasAndSpacesAreAccepted() {
        assertEquals(6_037_672L, LootInput.parse("6,037,672"))
        assertEquals(6_037_672L, LootInput.parse(" 6 037 672 "))
    }

    @Test
    fun nonNumericOrNegativeValuesAreRejected() {
        assertNull(LootInput.parse("lots"))
        assertNull(LootInput.parse("-20"))
        assertNull(LootInput.parse("1.5m"))
        assertNull(LootInput.parse(Long.MAX_VALUE.toString() + "0"))
    }
}
