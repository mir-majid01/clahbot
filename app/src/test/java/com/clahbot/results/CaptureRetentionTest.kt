package com.clahbot.results

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureRetentionTest {

    private fun entry(base: String, bytes: Long, modified: Long) =
        CaptureRetention.Entry(base, listOf("$base.png", "$base.json", "$base.txt"), bytes, modified)

    @Test
    fun deletesOldestCapturesWhenTheCountIsOverTheLimit() {
        val entries = listOf(
            entry("old", 10, 1),
            entry("middle", 10, 2),
            entry("new", 10, 3)
        )
        assertEquals(setOf("old"), CaptureRetention.selectForDeletion(entries, maxCaptures = 2, maxBytes = 1000))
    }

    @Test
    fun deletesOldestCapturesWhenTheBytesAreOverTheLimit() {
        val entries = listOf(
            entry("old", 600, 1),
            entry("middle", 300, 2),
            entry("new", 300, 3)
        )
        assertEquals(setOf("old"), CaptureRetention.selectForDeletion(entries, maxCaptures = 10, maxBytes = 700))
    }

    @Test
    fun keepsTheNewestCaptureEvenWhenItIsOverTheLimit() {
        val entries = listOf(entry("only", 900, 1))
        assertEquals(emptySet<String>(), CaptureRetention.selectForDeletion(entries, maxCaptures = 1, maxBytes = 100))
    }

    @Test
    fun anEmptySetNeedsNoDeletion() {
        assertEquals(emptySet<String>(), CaptureRetention.selectForDeletion(emptyList(), maxCaptures = 1, maxBytes = 1))
    }
}
