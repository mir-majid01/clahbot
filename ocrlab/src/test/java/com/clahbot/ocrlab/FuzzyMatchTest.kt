package com.clahbot.ocrlab

import com.clahbot.ocr.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatchTest {

    @Test
    fun acceptsTheMissingLetterInVicoRY() {
        assertTrue(FuzzyMatch.matchesResult("VicoRY", "victory"))
        assertTrue(FuzzyMatch.matchesResult("VictoRY", "victory"))
        assertTrue(FuzzyMatch.matchesResult("VICTORY", "victory"))
        assertTrue(FuzzyMatch.matchesResult("Defeat", "defeat"))
    }

    @Test
    fun rejectsUnrelatedText() {
        assertFalse(FuzzyMatch.matchesResult("Troops expended:", "victory"))
        assertFalse(FuzzyMatch.matchesResult("Return Home", "victory"))
        assertFalse(FuzzyMatch.matchesResult("Available Loot", "victory"))
        assertFalse(FuzzyMatch.matchesResult("Searching for opponents", "defeat"))
    }

    @Test
    fun editDistanceIsSmallForTheKnownOcrErrors() {
        assertEquals(1, FuzzyMatch.levenshtein("vicory", "victory"))
        assertEquals(0, FuzzyMatch.levenshtein("defeat", "defeat"))
        assertEquals(7, FuzzyMatch.levenshtein("", "victory"))
    }
}
