package com.clahbot.data.models

/** One continuous Clash play session. Distinct from a playback run. */
data class GameSession(
    val id: Long,
    val startedAt: Long,
    val lastUpdatedAt: Long,
    val endedAt: Long? = null,
    val status: String = ACTIVE,
    val attackIds: List<Long> = emptyList(),
    val macroIds: List<Long> = emptyList(),
    val attackCount: Int = 0,
    val incompleteAttacks: Int = 0,
    val gold: Long = 0,
    val elixir: Long = 0,
    val darkElixir: Long = 0,
    val bonusGold: Long = 0,
    val bonusElixir: Long = 0,
    val bonusDarkElixir: Long = 0,
    val stopReason: String? = null
) {
    val active: Boolean get() = status == ACTIVE

    val durationMs: Long
        get() = ((endedAt ?: lastUpdatedAt) - startedAt).coerceAtLeast(0L)

    companion object {
        const val ACTIVE = "active"
        const val CLOSED = "closed"
        const val RECOVERED = "recovered"
        const val SCHEMA_VERSION = 1
    }
}

/** Totals for display; loot read by a partial attack counts, missing loot is absent. */
data class SessionTotals(
    val attackCount: Int = 0,
    val incompleteAttacks: Int = 0,
    val gold: Long = 0,
    val elixir: Long = 0,
    val darkElixir: Long = 0,
    val bonusGold: Long = 0,
    val bonusElixir: Long = 0,
    val bonusDarkElixir: Long = 0,
    val durationMs: Long = 0
) {
    val completeAttacks: Int get() = attackCount - incompleteAttacks
}

object SessionAggregator {

    fun isIncomplete(record: AttackRecord): Boolean = record.needsReviewDefinition

    fun totals(records: List<AttackRecord>): SessionTotals {
        var gold = 0L
        var elixir = 0L
        var dark = 0L
        var bonusGold = 0L
        var bonusElixir = 0L
        var bonusDark = 0L
        var incomplete = 0
        var duration = 0L
        for (record in records) {
            if (!record.confirmed) continue
            duration += record.durationMs.coerceAtLeast(0L)
            if (isIncomplete(record)) incomplete++
            // An incomplete read is missing some loot; what it did read is still
            // real. Skipping the whole record here kept every flagged attack out
            // of the totals, which is why Lifetime could sit at zero for ever.
            gold += record.gold ?: 0L
            elixir += record.elixir ?: 0L
            dark += record.darkElixir ?: 0L
            bonusGold += record.bonusGold ?: 0L
            bonusElixir += record.bonusElixir ?: 0L
            bonusDark += record.bonusDarkElixir ?: 0L
        }
        return SessionTotals(
            attackCount = records.count { it.confirmed },
            incompleteAttacks = incomplete,
            gold = gold,
            elixir = elixir,
            darkElixir = dark,
            bonusGold = bonusGold,
            bonusElixir = bonusElixir,
            bonusDarkElixir = bonusDark,
            durationMs = duration
        )
    }

}

/** Total displayed per resource: loot plus bonus, never merged in storage. */
val SessionTotals.totalGold: Long get() = gold + bonusGold
val SessionTotals.totalElixir: Long get() = elixir + bonusElixir
val SessionTotals.totalDarkElixir: Long get() = darkElixir + bonusDarkElixir
