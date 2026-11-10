package com.clahbot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MacroNamingTest {

    @Test
    fun unusedDefaultIsKept() {
        assertEquals(
            "Macro Nov 02 14:21",
            MacroNaming.uniqueDefault("Macro Nov 02 14:21", listOf("Hii", "Macro Nov 02 14:20"))
        )
    }

    @Test
    fun usedDefaultGetsNumberedSuffix() {
        assertEquals(
            "Macro Nov 02 14:21 2",
            MacroNaming.uniqueDefault("Macro Nov 02 14:21", listOf("Macro Nov 02 14:21"))
        )
    }

    @Test
    fun suffixSkipsNamesThatAreAlreadyTaken() {
        assertEquals(
            "Macro Nov 02 14:21 4",
            MacroNaming.uniqueDefault(
                "Macro Nov 02 14:21",
                listOf(
                    "Macro Nov 02 14:21",
                    "Macro Nov 02 14:21 2",
                    "Macro Nov 02 14:21 3"
                )
            )
        )
    }

    @Test
    fun comparisonIgnoresCaseAndOuterSpaces() {
        assertEquals(
            "Macro Nov 02 14:21 2",
            MacroNaming.uniqueDefault("Macro Nov 02 14:21", listOf("  macro nov 02 14:21 "))
        )
    }

    @Test
    fun anExactValidNameIsAccepted() {
        assertNull(MacroNaming.validationError("Farm  LOOP", listOf("Other macro")))
        assertNull(MacroNaming.validationError("x".repeat(40), emptyList()))
    }

    @Test
    fun blankNamesAreRejected() {
        assertNotNull(MacroNaming.validationError("   ", emptyList()))
    }

    @Test
    fun overlongNamesAreRejectedInsteadOfTruncated() {
        assertNotNull(MacroNaming.validationError("x".repeat(41), emptyList()))
    }

    @Test
    fun duplicateNamesAreRejectedIgnoringCaseAndOuterSpaces() {
        assertNotNull(
            MacroNaming.validationError("Farm Loop", listOf("  farm loop  "))
        )
    }
}
