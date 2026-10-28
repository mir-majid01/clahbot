package com.clahbot.results

import com.clahbot.ocr.ResultsRead

/**
 * Accepts a loot read only after two consecutive complete reads of the same
 * screen agree on outcome and all three main values. Partial reads are ignored
 * while waiting, so a mid-animation frame cannot become the saved value.
 */
class StableReadGate {

    private var lastComplete: ResultsRead? = null
    private var stable: ResultsRead? = null

    fun offer(read: ResultsRead): ResultsRead? {
        if (!read.lootComplete) return null
        val previous = lastComplete
        lastComplete = read
        if (previous != null && sameMain(previous, read)) {
            stable = mergeBonus(read, previous)
            return stable
        }
        return null
    }

    fun stableRead(): ResultsRead? = stable

    private fun mergeBonus(latest: ResultsRead, earlier: ResultsRead): ResultsRead =
        latest.copy(
            bonusGold = latest.bonusGold ?: earlier.bonusGold,
            bonusElixir = latest.bonusElixir ?: earlier.bonusElixir,
            bonusDarkElixir = latest.bonusDarkElixir ?: earlier.bonusDarkElixir,
            bonusPanel = latest.bonusPanel || earlier.bonusPanel
        )

    companion object {
        fun sameMain(a: ResultsRead, b: ResultsRead): Boolean =
            a.outcome == b.outcome &&
                a.gold == b.gold &&
                a.elixir == b.elixir &&
                a.darkElixir == b.darkElixir
    }
}
