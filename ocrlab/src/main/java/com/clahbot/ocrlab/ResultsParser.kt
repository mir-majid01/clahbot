package com.clahbot.ocrlab

import java.util.Locale

enum class ResourceKind { GOLD, ELIXIR, DARK }

enum class ResultsOutcome { VICTORY, DEFEAT }

enum class RowRole { LOOT, BONUS, BALANCE }

fun interface IconColorSampler {
    fun classify(numberBox: OcrBox): ResourceKind?
}

data class RowCandidate(
    val box: OcrBox,
    val rawText: String,
    val role: RowRole,
    val resource: ResourceKind?
)

data class AssignedValues(
    val gold: Long?,
    val elixir: Long?,
    val dark: Long?
)

data class Inspection(
    val outcome: ResultsOutcome,
    val lootRows: List<RowCandidate>,
    val bonusRows: List<RowCandidate>,
    val balanceRows: List<RowCandidate>,
    val fullLoot: AssignedValues,
    val fullBonus: AssignedValues,
    val balances: Triple<Long?, Long?, Long?>,
    val issues: List<String>
)

data class ResultsRead(
    val outcome: ResultsOutcome,
    val stars: Int?,
    val gold: Long?,
    val elixir: Long?,
    val darkElixir: Long?,
    val bonusGold: Long?,
    val bonusElixir: Long?,
    val bonusDarkElixir: Long?,
    val balanceGold: Long?,
    val balanceElixir: Long?,
    val balanceDarkElixir: Long?,
    val confidence: Double,
    val needsReview: Boolean,
    val issues: List<String>
) {
    val lootComplete: Boolean
        get() = gold != null && elixir != null && darkElixir != null

    /** Stable across repeated captures of the same result, not across new loot. */
    val fingerprint: String = listOf(
        outcome.name,
        gold?.toString() ?: "?",
        elixir?.toString() ?: "?",
        darkElixir?.toString() ?: "?",
        bonusGold?.toString() ?: "?",
        bonusElixir?.toString() ?: "?",
        bonusDarkElixir?.toString() ?: "?"
    ).joinToString("|")
}

sealed interface ScreenRead {
    data class Results(val read: ResultsRead) : ScreenRead
    data class Ignored(val reason: String) : ScreenRead
}

/**
 * Anchor-based results-screen parser.
 *
 * "You got", "Victory"/"Defeat" and "Total damage" are all required; "Return
 * Home" is never an anchor because the searching screen also has it. Damage is
 * not read; the anchor only classifies the screen. Loot is assigned to a
 * resource by icon colour, never by row order. Values must pass the grouped
 * numeric format; failed reads stay null.
 */
class ResultsScreenParser(
    private val colorSampler: IconColorSampler?
) {

    fun parse(page: OcrPage): ScreenRead {
        val inspection = inspect(page) ?: return ScreenRead.Ignored(ignoredReason(page))
        return ScreenRead.Results(
            buildRead(inspection, lootValues = null, bonusValues = null, balances = null)
        )
    }

    fun inspect(page: OcrPage): Inspection? {
        val anchors = findAnchors(page) ?: return null
        val issues = mutableListOf<String>()
        val outcome = anchors.outcome
        val lootRows = readLootRows(page, anchors.youGot, issues)
        val bonusRows = if (outcome == ResultsOutcome.VICTORY) {
            readBonusRows(page, anchors.bonus, anchors.youGot, issues)
        } else {
            emptyList()
        }
        val balanceRows = readBalanceRows(page)
        val fullLoot = assignFull(lootRows, "loot", issues) { NumberParser.parseGrouped(it) }
        val fullBonus = assignFull(bonusRows, "bonus", issues) { NumberParser.parseBonus(it) }
        val balances = fullBalances(balanceRows, issues)
        return Inspection(
            outcome = outcome,
            lootRows = lootRows,
            bonusRows = bonusRows,
            balanceRows = balanceRows,
            fullLoot = fullLoot,
            fullBonus = fullBonus,
            balances = balances,
            issues = issues
        )
    }

    /**
     * Builds the final read. When [lootValues]/[bonusValues] are supplied they
     * align with the row candidates and come from the dual-engine crop pass.
     */
    fun buildRead(
        inspection: Inspection,
        lootValues: List<Long?>?,
        bonusValues: List<Long?>?,
        balances: Triple<Long?, Long?, Long?>?
    ): ResultsRead {
        val usingCropValues = lootValues != null && bonusValues != null
        val issues = if (usingCropValues) {
            mutableListOf()
        } else {
            inspection.issues.toMutableList()
        }
        val loot = assignProvided(inspection.lootRows, lootValues, inspection.fullLoot, "loot", issues)
        val bonus = assignProvided(inspection.bonusRows, bonusValues, inspection.fullBonus, "bonus", issues)
        val balance = balances ?: inspection.balances
        val missingLoot = listOf(loot.gold, loot.elixir, loot.dark).count { it == null }
        var confidence = 0.55
        confidence += 0.1 * (3 - missingLoot)
        if (inspection.outcome == ResultsOutcome.DEFEAT) {
            confidence += 0.1
        } else if (bonus.gold != null && bonus.elixir != null && bonus.dark != null) {
            confidence += 0.1
        }
        if (balance.first != null && balance.second != null && balance.third != null) {
            confidence += 0.05
        }
        confidence -= issues.size.coerceAtMost(8) * 0.03
        confidence = confidence.coerceIn(0.05, 1.0)
        return ResultsRead(
            outcome = inspection.outcome,
            stars = null,
            gold = loot.gold,
            elixir = loot.elixir,
            darkElixir = loot.dark,
            bonusGold = bonus.gold,
            bonusElixir = bonus.elixir,
            bonusDarkElixir = bonus.dark,
            balanceGold = balance.first,
            balanceElixir = balance.second,
            balanceDarkElixir = balance.third,
            confidence = confidence,
            needsReview = missingLoot > 0 || issues.any {
                it.contains("unknown") || it.contains("duplicate") || it.contains("conflict") ||
                    it.contains("not captured")
            },
            issues = issues
        )
    }

    private fun ignoredReason(page: OcrPage): String {
        val youGot = page.lines.any { normalized(it.text).contains("yougot") }
        val result = page.lines.any {
            FuzzyMatch.matchesResult(it.text, "victory") ||
                FuzzyMatch.matchesResult(it.text, "defeat")
        }
        val damage = page.lines.any { normalized(it.text).contains("totaldamage") }
        return when {
            !youGot -> "no 'You got' anchor"
            !result -> "no Victory/Defeat anchor"
            !damage -> "no 'Total damage' anchor"
            else -> "anchors out of order"
        }
    }

    private fun findAnchors(page: OcrPage): Anchors? {
        val youGot = page.lines.firstOrNull { normalized(it.text).contains("yougot") } ?: return null
        val result = page.lines.firstOrNull {
            FuzzyMatch.matchesResult(it.text, "victory") ||
                FuzzyMatch.matchesResult(it.text, "defeat")
        } ?: return null
        val totalDamage = page.lines.firstOrNull {
            normalized(it.text).contains("totaldamage")
        } ?: return null
        val youGotBox = youGot.box
        val resultBox = result.box
        if (youGotBox != null && resultBox != null && resultBox.top >= youGotBox.top) return null
        val outcome = if (FuzzyMatch.matchesResult(result.text, "defeat")) {
            ResultsOutcome.DEFEAT
        } else {
            ResultsOutcome.VICTORY
        }
        val bonus = page.lines.firstOrNull { normalized(it.text).contains("bonus") }
        return Anchors(youGot, totalDamage, bonus, outcome)
    }

    private fun readLootRows(
        page: OcrPage,
        youGot: OcrLine,
        issues: MutableList<String>
    ): List<RowCandidate> {
        val youGotBox = youGot.box ?: return emptyList()
        val troops = page.lines.firstOrNull { normalized(it.text).contains("troops") }
        val top = youGotBox.bottom
        val bottom = troops?.box?.top
            ?.takeIf { it > top }
            ?: (top + page.height / 4).coerceAtMost(page.height)
        val centerX = youGotBox.centerX
        val minX = (centerX - page.width * 0.22).toInt()
        val maxX = (centerX + page.width * 0.15).toInt()

        val rows = page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (box.centerY < top || box.centerY > bottom) return@mapNotNull null
            if (box.centerX < minX || box.centerX > maxX) return@mapNotNull null
            if (line.text.contains("+")) return@mapNotNull null
            if (isNonLootText(line.text)) return@mapNotNull null
            if (!NumberParser.isNumericLike(line.text)) return@mapNotNull null
            RowCandidate(box, line.text, RowRole.LOOT, colorSampler?.classify(box))
        }.sortedBy { it.box.top }

        val deduped = dedupe(rows).take(3)
        if (deduped.size < 3) issues += "loot rows found ${deduped.size}/3"
        return deduped
    }

    private fun readBonusRows(
        page: OcrPage,
        bonusAnchor: OcrLine?,
        youGot: OcrLine,
        issues: MutableList<String>
    ): List<RowCandidate> {
        val anchor = bonusAnchor ?: return emptyList()
        val anchorBox = anchor.box ?: return emptyList()
        val top = anchorBox.bottom
        val bottom = (youGot.box?.bottom ?: anchorBox.bottom) + page.height / 4
        val minX = anchorBox.centerX - page.width * 0.12
        val rows = page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (box.centerY < top || box.centerY > bottom) return@mapNotNull null
            if (box.centerX < minX) return@mapNotNull null
            if (normalized(line.text).contains("bonus")) return@mapNotNull null
            if (!NumberParser.isNumericLike(line.text, allowBonusPrefix = true)) {
                return@mapNotNull null
            }
            RowCandidate(box, line.text, RowRole.BONUS, colorSampler?.classify(box))
        }.sortedBy { it.box.top }
        val deduped = dedupe(rows).take(3)
        if (deduped.size < 3) issues += "bonus rows found ${deduped.size}/3"
        return deduped
    }

    private fun readBalanceRows(page: OcrPage): List<RowCandidate> {
        val rows = page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (box.centerY > page.height * 0.22) return@mapNotNull null
            if (box.centerX < page.width * 0.62) return@mapNotNull null
            val mapped = NumberParser.mapNumeric(line.text) ?: return@mapNotNull null
            if (mapped.count { it.isDigit() } < 4) return@mapNotNull null
            RowCandidate(box, line.text, RowRole.BALANCE, null)
        }.sortedBy { it.box.centerY }
        return rows.take(3).mapIndexed { index, row ->
            row.copy(
                resource = when (index) {
                    0 -> ResourceKind.GOLD
                    1 -> ResourceKind.ELIXIR
                    else -> ResourceKind.DARK
                }
            )
        }
    }

    private fun fullBalances(
        rows: List<RowCandidate>,
        issues: MutableList<String>
    ): Triple<Long?, Long?, Long?> {
        if (rows.size < 3) return Triple(null, null, null)
        val values = rows.map { row ->
            val value = NumberParser.parseGrouped(row.rawText)
            if (value == null) issues += "balance rejected: ${row.rawText}"
            value
        }
        return Triple(values[0], values[1], values[2])
    }

    private fun assignFull(
        rows: List<RowCandidate>,
        label: String,
        issues: MutableList<String>,
        parser: (String) -> Long?
    ): AssignedValues {
        var gold: Long? = null
        var elixir: Long? = null
        var dark: Long? = null
        for ((index, row) in rows.withIndex()) {
            val value = parser(row.rawText)
            if (value == null) {
                issues += "$label row ${index + 1}: rejected format '${row.rawText}'"
                continue
            }
            val resource = row.resource
            if (resource == null) {
                issues += "$label row ${index + 1}: icon colour unknown"
                continue
            }
            val existing = when (resource) {
                ResourceKind.GOLD -> gold
                ResourceKind.ELIXIR -> elixir
                ResourceKind.DARK -> dark
            }
            if (existing != null) {
                issues += "$label row ${index + 1}: duplicate ${resource.name.lowercase(Locale.US)}"
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

    private fun assignProvided(
        rows: List<RowCandidate>,
        provided: List<Long?>?,
        fallback: AssignedValues,
        label: String,
        issues: MutableList<String>
    ): AssignedValues {
        if (provided == null || provided.size != rows.size) return fallback
        var gold: Long? = null
        var elixir: Long? = null
        var dark: Long? = null
        for ((index, row) in rows.withIndex()) {
            val value = provided[index]
            if (value == null) {
                issues += "$label row ${index + 1}: not captured"
                continue
            }
            val resource = row.resource
            if (resource == null) {
                issues += "$label row ${index + 1}: icon colour unknown"
                continue
            }
            val existing = when (resource) {
                ResourceKind.GOLD -> gold
                ResourceKind.ELIXIR -> elixir
                ResourceKind.DARK -> dark
            }
            if (existing != null) {
                issues += "$label row ${index + 1}: duplicate ${resource.name.lowercase(Locale.US)}"
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

    private fun dedupe(rows: List<RowCandidate>): List<RowCandidate> {
        val result = mutableListOf<RowCandidate>()
        for (row in rows) {
            if (result.none { kotlin.math.abs(it.box.centerY - row.box.centerY) < 12 }) {
                result += row
            }
        }
        return result
    }

    private fun isNonLootText(text: String): Boolean {
        val normalized = normalized(text)
        return normalized.contains("bonus") ||
            normalized.contains("totaldamage") ||
            normalized.contains("yougot") ||
            normalized.contains("troops") ||
            normalized.contains("damage") ||
            normalized.contains("returnhome")
    }

    private fun normalized(text: String): String =
        text.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    private data class Anchors(
        val youGot: OcrLine,
        val totalDamage: OcrLine,
        val bonus: OcrLine?,
        val outcome: ResultsOutcome
    )
}
