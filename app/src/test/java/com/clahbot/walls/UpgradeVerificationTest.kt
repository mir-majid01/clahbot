package com.clahbot.walls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upgrade verification (Auto Walls 2.0 PRD §9). A confirm tap is only an
 * attempt; these tests prove an upgrade is called verified solely when a fresh
 * re-scan shows a wall actually moved between level groups, and that every
 * unreadable, unchanged or contradictory reading stays unverified.
 */
class UpgradeVerificationTest {

    private fun row(
        level: Int,
        count: Int?,
        currency: String = "gold",
        price: Long = 2_500_000,
        listIndex: Int = 0
    ) = WallCandidate(
        id = "row:$listIndex",
        level = level,
        price = price,
        currency = currency,
        listIndex = listIndex,
        confidence = 0.9f,
        count = count
    )

    private fun verified(result: UpgradeVerification.Result): String {
        assertTrue("expected Verified but was $result", result is UpgradeVerification.Result.Verified)
        return (result as UpgradeVerification.Result.Verified).detail
    }

    private fun unverified(result: UpgradeVerification.Result): String {
        assertTrue("expected Unverified but was $result", result is UpgradeVerification.Result.Unverified)
        return (result as UpgradeVerification.Result.Unverified).reason
    }

    @Test
    fun `a wall leaving its level group verifies the upgrade`() {
        val before = listOf(row(17, 317), row(18, 4, listIndex = 1))
        val after = listOf(row(17, 316), row(18, 5, listIndex = 1))
        val detail = verified(UpgradeVerification.verify(before, before[0], after))
        assertTrue(detail.contains("317->316"))
    }

    @Test
    fun `the next level gaining a wall verifies even when the upgraded row scrolled away`() {
        val before = listOf(row(17, 317), row(18, 4, listIndex = 1))
        val after = listOf(row(18, 5, listIndex = 0))
        val detail = verified(UpgradeVerification.verify(before, before[0], after))
        assertTrue(detail.contains("4->5"))
    }

    @Test
    fun `unchanged counts are not an upgrade`() {
        val before = listOf(row(17, 317), row(18, 4, listIndex = 1))
        val reason = unverified(UpgradeVerification.verify(before, before[0], before))
        assertTrue(reason.contains("unchanged"))
    }

    @Test
    fun `an empty re-scan is never verified`() {
        val before = listOf(row(17, 317))
        val reason = unverified(UpgradeVerification.verify(before, before[0], emptyList()))
        assertTrue(reason.contains("not readable"))
    }

    @Test
    fun `a missing row without corroboration is not verified`() {
        val before = listOf(row(17, 1), row(18, 4, listIndex = 1))
        val after = listOf(row(18, 4, listIndex = 0))
        unverified(UpgradeVerification.verify(before, before[0], after))
    }

    @Test
    fun `the last wall of a group verifies when the next level gains one`() {
        val before = listOf(row(17, 1), row(18, 4, listIndex = 1))
        val after = listOf(row(18, 5, listIndex = 0))
        verified(UpgradeVerification.verify(before, before[0], after))
    }

    @Test
    fun `rows are matched by level and currency, never by position`() {
        val before = listOf(row(13, 230, listIndex = 0), row(17, 317, listIndex = 1))
        // Same groups, new positions and new row keys after a scroll.
        val after = listOf(row(17, 316, listIndex = 0), row(13, 230, listIndex = 1))
        verified(UpgradeVerification.verify(before, before[1], after))
    }

    @Test
    fun `a different currency group at the same level is not the upgraded one`() {
        val before = listOf(row(17, 317, "gold"), row(17, 9, "elixir", listIndex = 1))
        val after = listOf(row(17, 317, "gold"), row(17, 8, "elixir", listIndex = 1))
        unverified(UpgradeVerification.verify(before, before[0], after))
    }

    @Test
    fun `unreadable counts are not verified`() {
        val before = listOf(row(17, 317))
        val after = listOf(row(17, null))
        val reason = unverified(UpgradeVerification.verify(before, before[0], after))
        assertTrue(reason.contains("not readable"))
    }

    @Test
    fun `a count jumping by more than one wall is not verified`() {
        val before = listOf(row(17, 317))
        val after = listOf(row(17, 315))
        unverified(UpgradeVerification.verify(before, before[0], after))
    }

    @Test
    fun `a resource drop alone never verifies an upgrade`() {
        val before = listOf(row(17, 317))
        val reason = unverified(
            UpgradeVerification.verify(
                before, before[0], before,
                resourcesBefore = WallCounters.Reading(gold = 5_000_000),
                resourcesAfter = WallCounters.Reading(gold = 2_500_000)
            )
        )
        assertTrue(reason.contains("unchanged"))
    }

    @Test
    fun `a resource change contradicting the price blocks verification`() {
        val before = listOf(row(17, 317))
        val after = listOf(row(17, 316))
        val reason = unverified(
            UpgradeVerification.verify(
                before, before[0], after,
                resourcesBefore = WallCounters.Reading(gold = 5_000_000),
                resourcesAfter = WallCounters.Reading(gold = 5_000_000)
            )
        )
        assertTrue(reason.contains("resource"))
    }

    @Test
    fun `a matching resource drop is reported as supporting evidence`() {
        val before = listOf(row(17, 317))
        val after = listOf(row(17, 316))
        val detail = verified(
            UpgradeVerification.verify(
                before, before[0], after,
                resourcesBefore = WallCounters.Reading(gold = 5_000_000),
                resourcesAfter = WallCounters.Reading(gold = 2_500_000)
            )
        )
        assertTrue(detail.contains("2500000"))
    }

    @Test
    fun `duplicate rows for one level are ambiguous and never verified`() {
        val before = listOf(row(17, 317))
        val after = listOf(row(17, 316), row(17, 316, listIndex = 1))
        val reason = unverified(UpgradeVerification.verify(before, before[0], after))
        assertTrue(reason.contains("ambiguous"))
    }

    @Test
    fun `a re-scan showing only unrelated levels is not verified`() {
        val before = listOf(row(17, 317))
        val after = listOf(row(13, 230, "elixir"))
        unverified(UpgradeVerification.verify(before, before[0], after))
    }

    @Test
    fun `the elixir group is verified against elixir counters, not gold`() {
        val before = listOf(row(16, 12, "elixir", price = 1_000_000))
        val after = listOf(row(16, 11, "elixir", price = 1_000_000))
        val detail = verified(
            UpgradeVerification.verify(
                before, before[0], after,
                resourcesBefore = WallCounters.Reading(gold = 9_000_000, elixir = 4_000_000),
                resourcesAfter = WallCounters.Reading(gold = 9_000_000, elixir = 3_000_000)
            )
        )
        assertTrue(detail.contains("1000000"))
    }

    @Test
    fun `a wrong-currency resource drop contradicts the upgrade`() {
        val before = listOf(row(16, 12, "elixir", price = 1_000_000))
        val after = listOf(row(16, 11, "elixir", price = 1_000_000))
        val reason = unverified(
            UpgradeVerification.verify(
                before, before[0], after,
                resourcesBefore = WallCounters.Reading(gold = 9_000_000, elixir = 4_000_000),
                resourcesAfter = WallCounters.Reading(gold = 8_000_000, elixir = 4_000_000)
            )
        )
        assertTrue(reason.contains("resource"))
    }

    @Test
    fun `verification never claims a level the re-scan did not show`() {
        val before = listOf(row(17, 317), row(18, 4, listIndex = 1))
        val after = listOf(row(17, 316), row(18, 4, listIndex = 1))
        val detail = verified(UpgradeVerification.verify(before, before[0], after))
        assertEquals(true, detail.contains("317->316"))
        assertTrue(!detail.contains("4->5"))
    }
}