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
    val bonusDarkElixir: Long = 0
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

/** Totals for display; incomplete records are counted but excluded from loot. */
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

    fun isIncomplete(record: AttackRecord): Boolean {
        if (!record.confirmed) return true
        if (record.gold == null || record.elixir == null || record.darkElixir == null) {
            return true
        }
        return record.bonusState == AttackRecord.BONUS_NOT_CAPTURED
    }

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
            if (isIncomplete(record)) {
                incomplete++
                continue
            }
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
