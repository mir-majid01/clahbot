package com.clahbot.results

import com.clahbot.ocr.ResultsRead

/**
 * Hard plausibility limits and a conservative balance-delta check. Anything
 * flagged here is kept for review but is not written as a normal capture.
 */
object LootSanity {

    const val MAX_GOLD = 10_000_000L
    const val MAX_ELIXIR = 10_000_000L
    const val MAX_DARK = 1_000_000L
    const val MAX_BONUS_GOLD = 1_000_000L
    const val MAX_BONUS_ELIXIR = 1_000_000L
    const val MAX_BONUS_DARK = 100_000L

    data class Sanitized(val read: ResultsRead, val reasons: List<String>)

    fun sanitize(read: ResultsRead): Sanitized {
        val reasons = mutableListOf<String>()
        fun take(value: Long?, max: Long, label: String): Long? {
            if (value == null) return null
            if (value > max) {
                reasons += "$label $value is above the plausible maximum ($max)"
                return null
            }
            return value
        }
        val sanitized = read.copy(
            gold = take(read.gold, MAX_GOLD, "Gold"),
            elixir = take(read.elixir, MAX_ELIXIR, "Elixir"),
            darkElixir = take(read.darkElixir, MAX_DARK, "Dark elixir"),
            bonusGold = take(read.bonusGold, MAX_BONUS_GOLD, "Bonus gold"),
            bonusElixir = take(read.bonusElixir, MAX_BONUS_ELIXIR, "Bonus elixir"),
            bonusDarkElixir = take(read.bonusDarkElixir, MAX_BONUS_DARK, "Bonus dark elixir")
        )
        return Sanitized(sanitized, reasons)
    }

    data class Balances(val gold: Long?, val elixir: Long?, val dark: Long?)

    /**
     * Review-only check. A balance can legitimately grow by more than the
     * loot (other farming), but it cannot grow by less unless the user spent
     * resources between the two screens. A 25% tolerance avoids flagging a
     * small purchase; a clearly impossible 25M gold value is still caught.
     */
    fun balanceReasons(
        read: ResultsRead,
        previous: Balances?,
        current: Balances
    ): List<String> {
        if (previous == null) return emptyList()
        val reasons = mutableListOf<String>()

        fun check(
            label: String,
            expected: Long?,
            before: Long?,
            after: Long?,
            fixedCost: Long = 0L
        ) {
            if (expected == null || before == null || after == null) return
            val delta = after - before
            val tolerance = 50_000L + expected / 4
            if (delta + tolerance < expected - fixedCost) {
                reasons += "$label loot $expected is inconsistent with the balance change $delta"
            }
        }

        val bonusGold = read.bonusGold ?: 0L
        val bonusElixir = read.bonusElixir ?: 0L
        val bonusDark = read.bonusDarkElixir ?: 0L
        check("Gold", read.gold?.plus(bonusGold), previous.gold, current.gold)
        check("Elixir", read.elixir?.plus(bonusElixir), previous.elixir, current.elixir)
        check("Dark elixir", read.darkElixir?.plus(bonusDark), previous.dark, current.dark)
        return reasons
    }

    fun balanceOf(read: ResultsRead) = Balances(
        gold = read.balanceGold,
        elixir = read.balanceElixir,
        dark = read.balanceDarkElixir
    )
}
