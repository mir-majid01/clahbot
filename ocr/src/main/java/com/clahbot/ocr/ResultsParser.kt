package com.clahbot.ocr

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

/**
 * Anchor-based results-screen row detector.
 *
 * "You got", "Victory"/"Defeat" and "Total damage" are all required. Loot
 * rows must sit in the right-aligned value column and have a recognised
 * resource icon, which keeps the Sour Elixir panel and label text out. Values
 * are not assigned here: the accuracy harness feeds every row to all readers.
 */
class ResultsScreenParser(
    private val colorSampler: IconColorSampler?
) {

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
        val balanceRows = readBalanceRows(page, issues)
        return Inspection(
            outcome = outcome,
            lootRows = lootRows,
            bonusRows = bonusRows,
            balanceRows = balanceRows,
            issues = issues
        )
    }

    fun ignoredReason(page: OcrPage): String {
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
        page.lines.firstOrNull {
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
        return Anchors(youGot, bonus, outcome)
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

        // The loot values are right-aligned under "You got". A wide left margin
        // still contains a clipped leading digit but not the Sour Elixir panel.
        val columnCenter = youGotBox.centerX
        val rightTolerance = (page.width * 0.05).toInt()
        val columnLeft = (columnCenter - page.width * 0.16).toInt()
        val rightMin = columnCenter - rightTolerance
        val rightMax = columnCenter + rightTolerance

        val rows = page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (box.centerY < top || box.centerY > bottom) return@mapNotNull null
            if (box.right < rightMin || box.right > rightMax) return@mapNotNull null
            if (box.left < columnLeft) return@mapNotNull null
            if (line.text.contains("+")) return@mapNotNull null
            if (isNonLootText(line.text)) return@mapNotNull null
            if (NumberParser.lootCandidates(line.text).isEmpty()) return@mapNotNull null
            val resource = colorSampler?.classify(box)
            if (resource == null) {
                issues += "loot row '${line.text}': icon colour unknown"
                return@mapNotNull null
            }
            RowCandidate(box, line.text, RowRole.LOOT, resource)
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
        val minX = anchorBox.centerX - page.width * 0.06
        val maxX = anchorBox.centerX + page.width * 0.08
        val rows = page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (box.centerY < top || box.centerY > bottom) return@mapNotNull null
            if (box.centerX < minX || box.centerX > maxX) return@mapNotNull null
            if (normalized(line.text).contains("bonus")) return@mapNotNull null
            if (NumberParser.bonusCandidates(line.text).isEmpty()) return@mapNotNull null
            val resource = colorSampler?.classify(box)
            if (resource == null) {
                issues += "bonus row '${line.text}': icon colour unknown"
                return@mapNotNull null
            }
            RowCandidate(box, line.text, RowRole.BONUS, resource)
        }.sortedBy { it.box.top }
        val deduped = dedupe(rows).take(3)
        if (deduped.size < 3) issues += "bonus rows found ${deduped.size}/3"
        return deduped
    }

    private fun readBalanceRows(
        page: OcrPage,
        issues: MutableList<String>
    ): List<RowCandidate> {
        val rows = page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (box.centerY > page.height * 0.22) return@mapNotNull null
            if (box.centerX < page.width * 0.62) return@mapNotNull null
            if (NumberParser.balanceCandidates(line.text).isEmpty()) return@mapNotNull null
            RowCandidate(box, line.text, RowRole.BALANCE, null)
        }.sortedBy { it.box.centerY }
        val assigned = rows.take(3).mapIndexed { index, row ->
            row.copy(
                resource = when (index) {
                    0 -> ResourceKind.GOLD
                    1 -> ResourceKind.ELIXIR
                    else -> ResourceKind.DARK
                }
            )
        }
        if (assigned.size < 3) issues += "balance rows found ${assigned.size}/3"
        return assigned
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
            normalized.contains("sour") ||
            normalized.contains("totaldamage") ||
            normalized.contains("yougot") ||
            normalized.contains("troops") ||
            normalized.contains("damage") ||
            normalized.contains("returnhome") ||
            normalized.contains("findamatch") ||
            normalized.contains("storage")
    }

    private fun normalized(text: String): String =
        text.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    private data class Anchors(
        val youGot: OcrLine,
        val bonus: OcrLine?,
        val outcome: ResultsOutcome
    )
}
