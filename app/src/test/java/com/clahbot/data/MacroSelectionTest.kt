package com.clahbot.data

import com.clahbot.data.models.Macro
import org.junit.Assert.assertEquals
import org.junit.Test

class MacroSelectionTest {

    private fun macro(id: Long) = Macro(id = id, name = "Macro $id")

    @Test
    fun keepsSelectionThatStillExists() {
        assertEquals(2L, MacroSelection.reconcile(2L, listOf(macro(1), macro(2))))
    }

    @Test
    fun clearsSelectionAfterDeletion() {
        assertEquals(-1L, MacroSelection.reconcile(2L, listOf(macro(1))))
    }

    @Test
    fun clearsEmptySelection() {
        assertEquals(-1L, MacroSelection.reconcile(-1L, listOf(macro(1))))
    }

    @Test
    fun clearsSelectionForEmptyLibrary() {
        assertEquals(-1L, MacroSelection.reconcile(5L, emptyList()))
    }
}
