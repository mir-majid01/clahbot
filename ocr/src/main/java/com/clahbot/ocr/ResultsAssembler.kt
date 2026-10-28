package com.clahbot.ocr

/**
 * Turns resolved row readings into a [ResultsRead]. Only consensus/balance
 * values reach this point; missing values stay null and are reported.
 */
object ResultsAssembler {

    fun assemble(inspection: Inspection, rows: List<RowReading>): ResultsRead {
        val issues = inspection.issues.toMutableList()
        val lootRows = rows.filter { it.role == RowRole.LOOT }.sortedBy { it.index }
        val bonusRows = rows.filter { it.role == RowRole.BONUS }.sortedBy { it.index }
        val balanceRows = rows.filter { it.role == RowRole.BALANCE }.sortedBy { it.index }

        val loot = assign(lootRows, "loot", issues)
        val bonus = assign(bonusRows, "bonus", issues)
        val balance = assign(balanceRows, "balance", issues)

        val missingLoot = listOf(loot.gold, loot.elixir, loot.dark).count { it == null }
        var confidence = 0.55
        confidence += 0.1 * (3 - missingLoot)
        if (inspection.outcome == ResultsOutcome.DEFEAT) {
            confidence += 0.1
        } else if (bonus.gold != null && bonus.elixir != null && bonus.dark != null) {
            confidence += 0.1
        }
        if (balance.gold != null && balance.elixir != null && balance.dark != null) {
            confidence += 0.05
        }
        confidence -= issues.size.coerceAtMost(8) * 0.03
        confidence = confidence.coerceIn(0.05, 1.0)

        val unresolved = rows.any {
            it.combined == null || (it.status != "consensus" && it.status != "balance_math")
        }
        val needsReview = missingLoot > 0 ||
            unresolved ||
            issues.any {
                it.contains("unknown") || it.contains("duplicate") ||
                    it.contains("conflict") || it.contains("not captured") ||
                    it.contains("found 0/3") || it.contains("found 1/3") ||
                    it.contains("found 2/3")
            } ||
            (inspection.bonusPanel &&
                (bonus.gold == null || bonus.elixir == null || bonus.dark == null))

        return ResultsRead(
            outcome = inspection.outcome,
            stars = null,
            gold = loot.gold,
            elixir = loot.elixir,
            darkElixir = loot.dark,
            bonusGold = bonus.gold,
            bonusElixir = bonus.elixir,
            bonusDarkElixir = bonus.dark,
            balanceGold = balance.gold,
            balanceElixir = balance.elixir,
            balanceDarkElixir = balance.dark,
            confidence = confidence,
            needsReview = needsReview,
            issues = issues,
            bonusPanel = inspection.bonusPanel
        )
    }

    private fun assign(
        rows: List<RowReading>,
        label: String,
        issues: MutableList<String>
    ): AssignedValues {
        var gold: Long? = null
        var elixir: Long? = null
        var dark: Long? = null
        for (row in rows) {
            val resource = row.resource
            if (resource == null) {
                issues += "$label row ${row.index + 1}: resource unknown"
                continue
            }
            val value = row.combined
            if (value == null) {
                issues += "$label row ${row.index + 1}: not captured (${row.status})"
                continue
            }
            val existing = when (resource) {
                ResourceKind.GOLD -> gold
                ResourceKind.ELIXIR -> elixir
                ResourceKind.DARK -> dark
            }
            if (existing != null) {
                issues += "$label row ${row.index + 1}: duplicate ${resource.name.lowercase()}"
                continue
            }
            when (resource) {
                ResourceKind.GOLD -> gold = value
                ResourceKind.ELIXIR -> elixir = value
                ResourceKind.DARK -> dark = value
            }
        }
        return AssignedValues(gold, elixir, dark)
    }
}
