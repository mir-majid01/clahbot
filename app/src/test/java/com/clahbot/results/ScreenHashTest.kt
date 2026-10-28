package com.clahbot.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenHashTest {

    private fun alternating(first: Int) = IntArray(64) { index ->
        if (index % 2 == 0) first else 255 - first
    }

    @Test
    fun samePixelsProduceTheSameHash() {
        assertEquals(ScreenHash.fromLuma(alternating(0)), ScreenHash.fromLuma(alternating(0)))
    }

    @Test
    fun differentPixelsProduceADifferentHash() {
        assertNotEquals(ScreenHash.fromLuma(alternating(0)), ScreenHash.fromLuma(alternating(255)))
    }

    @Test
    fun hashIsSixteenHexCharacters() {
        assertEquals(16, ScreenHash.fromLuma(alternating(0)).length)
    }

    @Test
    fun distanceCountsDifferingBitsAndRejectsMissingHashes() {
        assertEquals(0, ScreenHash.distance("0000000000000000", "0000000000000000"))
        assertEquals(64, ScreenHash.distance("0000000000000000", "ffffffffffffffff"))
        assertEquals(1, ScreenHash.distance("0000000000000000", "0000000000000001"))
        assertEquals(null, ScreenHash.distance(null, "0000000000000000"))
        assertEquals(null, ScreenHash.distance("not-a-hash", "0000000000000000"))
    }
}
