package com.clahbot.walls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The candidate-ranking engine (PRD §2.2 / §3.3). It decides *which* eligible
 * wall to upgrade from detected candidates — never from a fixed coordinate —
 * and drops anything it is not confident about instead of guessing.
 */
class WallSelectionTest {

    private fun candidate(
        id: String,
        level: Int,
        price: Long,
        listIndex: Int,
        confidence: Float = 0.9f,
        currency: String = "gold"
    ) = WallCandidate(
        id = id,
        level = level,
        price = price,
        currency = currency,
        listIndex = listIndex,
        confidence = confidence
    )

    private val walls = listOf(
        candidate("a", level = 8, price = 50_000, listIndex = 0),
        candidate("b", level = 6, price = 20_000, listIndex = 1),
        candidate("c", level = 9, price = 80_000, listIndex = 2)
    )

    @Test
    fun `cheapest first selects the lowest price`() {
        val picked = WallSelection.select(walls, WallSelectionConfig(WallSelectionStrategy.CHEAPEST_FIRST))
        assertEquals("b", picked?.id)
    }

    @Test
    fun `highest level first selects the highest level`() {
        val picked = WallSelection.select(walls, WallSelectionConfig(WallSelectionStrategy.HIGHEST_LEVEL_FIRST))
        assertEquals("c", picked?.id)
    }

    @Test
    fun `lowest level first selects the lowest level`() {
        val picked = WallSelection.select(walls, WallSelectionConfig(WallSelectionStrategy.LOWEST_LEVEL_FIRST))
        assertEquals("b", picked?.id)
    }

    @Test
    fun `first in list selects the earliest list entry`() {
        val picked = WallSelection.select(walls, WallSelectionConfig(WallSelectionStrategy.FIRST_IN_LIST))
        assertEquals("a", picked?.id)
    }

    @Test
    fun `custom priority applies the criteria in order with a list tie-break`() {
        // Highest level first, then cheapest among equal levels.
        val equal = listOf(
            candidate("x", level = 9, price = 70_000, listIndex = 0),
            candidate("y", level = 9, price = 40_000, listIndex = 1),
            candidate("z", level = 9, price = 40_000, listIndex = 2)
        )
        val config = WallSelectionConfig(
            strategy = WallSelectionStrategy.CUSTOM,
            customOrder = listOf(WallCriterion.HIGHEST_LEVEL, WallCriterion.CHEAPEST)
        )
        // y and z tie on level+price, so the list order breaks it: y (index 1).
        assertEquals("y", WallSelection.select(equal, config)?.id)
    }

    @Test
    fun `equal ranks fall back to list order deterministically`() {
        val equal = listOf(
            candidate("late", level = 7, price = 30_000, listIndex = 5),
            candidate("early", level = 7, price = 30_000, listIndex = 2)
        )
        assertEquals(
            "early",
            WallSelection.select(equal, WallSelectionConfig(WallSelectionStrategy.CHEAPEST_FIRST))?.id
        )
    }

    @Test
    fun `low-confidence candidates are never selected`() {
        val unsure = listOf(
            candidate("blurred", level = 5, price = 10_000, listIndex = 0, confidence = 0.3f)
        )
        assertNull(WallSelection.select(unsure, WallSelectionConfig()))
    }

    @Test
    fun `gold mode ignores elixir-priced walls`() {
        val mixed = listOf(
            candidate("goldy", level = 8, price = 10_000, listIndex = 0, currency = "gold"),
            candidate("elixiry", level = 9, price = 5_000, listIndex = 1, currency = "elixir")
        )
        val config = WallSelectionConfig(
            strategy = WallSelectionStrategy.CHEAPEST_FIRST,
            currency = WallCurrency.GOLD
        )
        // The elixir wall is cheaper, but Gold mode must never pick it.
        assertEquals("goldy", WallSelection.select(mixed, config)?.id)
    }

    @Test
    fun `elixir mode ignores gold walls`() {
        val mixed = listOf(
            candidate("goldy", level = 8, price = 10_000, listIndex = 0, currency = "gold"),
            candidate("elixiry", level = 9, price = 5_000, listIndex = 1, currency = "elixir")
        )
        val config = WallSelectionConfig(
            strategy = WallSelectionStrategy.CHEAPEST_FIRST,
            currency = WallCurrency.ELIXIR
        )
        assertEquals("elixiry", WallSelection.select(mixed, config)?.id)
    }

    @Test
    fun `both mode uses gold or elixir but never an unknown currency`() {
        val mixed = listOf(
            candidate("goldy", level = 8, price = 30_000, listIndex = 0, currency = "gold"),
            candidate("elixiry", level = 9, price = 20_000, listIndex = 1, currency = "elixir"),
            candidate("unknown", level = 5, price = 1_000, listIndex = 2, currency = "unknown")
        )
        val config = WallSelectionConfig(
            strategy = WallSelectionStrategy.CHEAPEST_FIRST,
            currency = WallCurrency.BOTH
        )
        assertEquals("elixiry", WallSelection.select(mixed, config)?.id)
    }

    @Test
    fun `no candidates selected from an empty list`() {
        assertNull(WallSelection.select(emptyList(), WallSelectionConfig()))
    }
}
