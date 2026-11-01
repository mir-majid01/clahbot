package com.clahbot.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PluralTest {

    @Test
    fun countsUseSingularOnlyForOne() {
        assertEquals("0 attacks", Plural.attacks(0))
        assertEquals("1 attack", Plural.attacks(1))
        assertEquals("2 attacks", Plural.attacks(2))
        assertEquals("1 macro", Plural.macros(1))
        assertEquals("3 macros", Plural.macros(1 + 2))
        assertEquals("1 session", Plural.sessions(1))
    }

    @Test
    fun needsReviewAgreesWithTheCount() {
        assertEquals("1 attack needs review", Plural.needReview(1))
        assertEquals("3 attacks need review", Plural.needReview(3))
    }

    @Test
    fun customPluralIsUsed() {
        assertEquals("1 saved macro", Plural.of(1, "saved macro"))
        assertEquals("2 saved macros", Plural.of(2, "saved macro"))
        assertEquals("2 saved macro records", Plural.of(2, "saved macro", "saved macro records"))
    }
}
