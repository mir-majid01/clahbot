package com.clahbot.walls

/** One detected wall-upgrade candidate from the Buildings & Heroes list (PRD §3.3). */
data class WallCandidate(
    /** Stable identity for this list entry (row key), not a screen coordinate. */
    val id: String,
    val level: Int,
    val targetLevel: Int? = null,
    /** Actual displayed upgrade price for this candidate. */
    val price: Long,
    /** Payment currency read from the entry ("gold", "elixir", "dark"). */
    val currency: String,
    /** Position in the current upgrade list; the deterministic tie-breaker. */
    val listIndex: Int,
    /** Recognition confidence for identity + price, 0..1. */
    val confidence: Float,
    /** Walls in this level group ("Wall x N"); null when the label is unreadable. */
    val count: Int? = null
)

/** How eligible walls are prioritised (PRD §2.2). Default is Cheapest first. */
enum class WallSelectionStrategy {
    CHEAPEST_FIRST,
    HIGHEST_LEVEL_FIRST,
    LOWEST_LEVEL_FIRST,
    FIRST_IN_LIST,
    CUSTOM
}

/** One step in a Custom priority order. */
enum class WallCriterion { CHEAPEST, HIGHEST_LEVEL, LOWEST_LEVEL }

/** Which displayed currency a wall row may use (PRD §5.3). */
enum class WallCurrency { GOLD, ELIXIR, BOTH }

data class WallSelectionConfig(
    val strategy: WallSelectionStrategy = WallSelectionStrategy.CHEAPEST_FIRST,
    /** Ordered criteria, only used when [strategy] is CUSTOM. */
    val customOrder: List<WallCriterion> = emptyList(),
    /** Pay-with setting: only consider rows shown in this currency. */
    val currency: WallCurrency = WallCurrency.BOTH,
    /** Candidates below this confidence are never selected (PRD §2.2, §3.3). */
    val minConfidence: Float = 0.6f
)

/**
 * Ranks wall-upgrade candidates by the user's configured strategy. It never
 * guesses: candidates below the confidence threshold are dropped, and equal
 * ranks fall back to the current list order so the choice is deterministic.
 * No fixed screen coordinate is involved — the ranking runs over detected
 * candidates (PRD §2.2).
 */
object WallSelection {

    fun eligibleCandidates(candidates: List<WallCandidate>, config: WallSelectionConfig): List<WallCandidate> =
        candidates.filter {
            it.confidence >= config.minConfidence && matchesCurrency(it.currency, config.currency)
        }

    /** Unknown/other currencies are never selected — only the chosen gold/elixir. */
    private fun matchesCurrency(currency: String, filter: WallCurrency): Boolean = when (filter) {
        WallCurrency.GOLD -> currency == "gold"
        WallCurrency.ELIXIR -> currency == "elixir"
        WallCurrency.BOTH -> currency == "gold" || currency == "elixir"
    }

    fun rank(candidates: List<WallCandidate>, config: WallSelectionConfig): List<WallCandidate> {
        val eligible = eligibleCandidates(candidates, config)
        if (eligible.isEmpty()) return emptyList()
        return eligible.sortedWith(comparator(config))
    }

    fun select(candidates: List<WallCandidate>, config: WallSelectionConfig): WallCandidate? =
        rank(candidates, config).firstOrNull()

    private fun comparator(config: WallSelectionConfig): Comparator<WallCandidate> {
        val listOrder: Comparator<WallCandidate> = compareBy { it.listIndex }
        return when (config.strategy) {
            WallSelectionStrategy.CHEAPEST_FIRST ->
                compareBy<WallCandidate> { it.price }.then(listOrder)

            WallSelectionStrategy.HIGHEST_LEVEL_FIRST ->
                compareByDescending<WallCandidate> { it.level }.then(listOrder)

            WallSelectionStrategy.LOWEST_LEVEL_FIRST ->
                compareBy<WallCandidate> { it.level }.then(listOrder)

            WallSelectionStrategy.FIRST_IN_LIST -> listOrder

            WallSelectionStrategy.CUSTOM -> {
                val order = config.customOrder.ifEmpty { listOf(WallCriterion.CHEAPEST) }
                val steps = order.map { criterion ->
                    when (criterion) {
                        WallCriterion.CHEAPEST -> compareBy<WallCandidate> { it.price }
                        WallCriterion.HIGHEST_LEVEL -> compareByDescending<WallCandidate> { it.level }
                        WallCriterion.LOWEST_LEVEL -> compareBy<WallCandidate> { it.level }
                    }
                }
                steps.reduce { acc, next -> acc.then(next) }.then(listOrder)
            }
        }
    }
}
