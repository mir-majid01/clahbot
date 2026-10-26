package com.clahbot.ocr

data class ExpectedLoot(
    val gold: Long?,
    val elixir: Long?,
    val dark: Long?
) {
    fun value(resource: ResourceKind): Long? = when (resource) {
        ResourceKind.GOLD -> gold
        ResourceKind.ELIXIR -> elixir
        ResourceKind.DARK -> dark
    }

    val any: Boolean get() = gold != null || elixir != null || dark != null
}

/**
 * Exact balance identity:
 * balance_after = balance_before - search_cost_gold + loot + bonus.
 *
 * It is used only between a defeat and the next victory, only when all three
 * balances and bonuses were captured, and only to confirm a value that at
 * least one reader already saw. It never invents a value.
 */
object BalanceMath {

    private const val MAX_PLAUSIBLE_LOOT = 100_000_000L

    fun expectedLoot(
        previous: ResultsRead?,
        current: ResultsRead,
        searchCostGold: Long?
    ): ExpectedLoot? {
        if (previous == null || searchCostGold == null) return null
        if (previous.outcome != ResultsOutcome.DEFEAT ||
            current.outcome != ResultsOutcome.VICTORY
        ) {
            return null
        }
        val prevGold = previous.balanceGold ?: return null
        val prevElixir = previous.balanceElixir ?: return null
        val prevDark = previous.balanceDarkElixir ?: return null
        val balanceGold = current.balanceGold ?: return null
        val balanceElixir = current.balanceElixir ?: return null
        val balanceDark = current.balanceDarkElixir ?: return null
        val bonusGold = current.bonusGold ?: return null
        val bonusElixir = current.bonusElixir ?: return null
        val bonusDark = current.bonusDarkElixir ?: return null

        val gold = balanceGold - prevGold + searchCostGold - bonusGold
        val elixir = balanceElixir - prevElixir - bonusElixir
        val dark = balanceDark - prevDark - bonusDark
        val expected = ExpectedLoot(
            gold = gold.takeIf { it in 0..MAX_PLAUSIBLE_LOOT },
            elixir = elixir.takeIf { it in 0..MAX_PLAUSIBLE_LOOT },
            dark = dark.takeIf { it in 0..MAX_PLAUSIBLE_LOOT }
        )
        return expected.takeIf { it.any }
    }

    fun crossCheck(
        previous: ResultsRead?,
        current: ResultsRead,
        searchCostGold: Long?
    ): String {
        if (previous == null || searchCostGold == null) return "not_available"
        if (previous.outcome != ResultsOutcome.DEFEAT ||
            current.outcome != ResultsOutcome.VICTORY
        ) {
            return "not_applicable"
        }
        val prevGold = previous.balanceGold ?: return "not_available"
        val prevElixir = previous.balanceElixir ?: return "not_available"
        val prevDark = previous.balanceDarkElixir ?: return "not_available"
        val gold = current.balanceGold ?: return "not_available"
        val elixir = current.balanceElixir ?: return "not_available"
        val dark = current.balanceDarkElixir ?: return "not_available"
        val lootGold = current.gold ?: return "not_available"
        val lootElixir = current.elixir ?: return "not_available"
        val lootDark = current.darkElixir ?: return "not_available"
        val bonusGold = current.bonusGold ?: return "not_available"
        val bonusElixir = current.bonusElixir ?: return "not_available"
        val bonusDark = current.bonusDarkElixir ?: return "not_available"

        val expectedGold = prevGold - searchCostGold + lootGold + bonusGold
        val expectedElixir = prevElixir + lootElixir + bonusElixir
        val expectedDark = prevDark + lootDark + bonusDark
        return if (expectedGold == gold &&
            expectedElixir == elixir &&
            expectedDark == dark
        ) {
            "pass"
        } else {
            "mismatch"
        }
    }
}
