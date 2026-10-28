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
    val issues: List<String>,
    /** True when a bonus panel/title exists, even if none of its rows were read. */
    val bonusPanel: Boolean = false
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
    val issues: List<String>,
    /** True when a bonus panel/title exists, even if none of its rows were read. */
    val bonusPanel: Boolean = false
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
            issues = issues,
            bonusPanel = anchors.bonus != null
        )
    }

    fun ignoredReason(page: OcrPage): String {
        val youGot = page.lines.any { normalized(it.text).contains("yougot") }
        val result = page.lines.any {
            FuzzyMatch.matchesResult(it.text, "victory") ||
                FuzzyMatch.matchesResult(it.text, "defeat")
        }
        val damage = page.lines.any { hasDamageAnchor(it.text) }
        return when {
            !youGot -> "no 'You got' anchor"
            !result -> "no Victory/Defeat anchor"
            !damage -> "no damage anchor"
            else -> "anchors out of order"
        }
    }

    private fun hasDamageAnchor(text: String): Boolean {
        val value = normalized(text)
        return value.contains("totaldamage") || value.contains("totaldestruction")
    }

    private fun findAnchors(page: OcrPage): Anchors? {
        val youGot = page.lines.firstOrNull { normalized(it.text).contains("yougot") } ?: return null
        val result = page.lines.firstOrNull {
            FuzzyMatch.matchesResult(it.text, "victory") ||
                FuzzyMatch.matchesResult(it.text, "defeat")
        } ?: return null
        page.lines.firstOrNull {
            hasDamageAnchor(it.text)
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

        // The result panel always uses three vertically ordered loot rows.
        // Their pitch is fixed by the panel, while the distance down to
        // "Troops expended" changes with the layout: war results pack the rows
        // tightly near the anchor and leave a large empty band below. Dividing
        // the anchor-to-troops span therefore drifts into the troop row. OCR
        // text is used only as a hint for the full-page reader; the crop
        // readers read the fixed slot.
        val top = youGotBox.bottom
        val pitch = (page.height * 0.070f).coerceAtLeast(40f)
        val firstCenter = top + pitch * 0.45f
        val columnCenter = youGotBox.centerX
        val columnLeft = (columnCenter - page.width * 0.16).toInt()
        val columnRight = (columnCenter + page.width * 0.10).toInt()
        val zeroLeft = (columnCenter + page.width * 0.002).toInt()
        val zeroRight = (columnCenter + page.width * 0.016).toInt()
        val rightTolerance = (page.width * 0.05).toInt()
        val rightMin = columnCenter - rightTolerance
        val rightMax = columnCenter + rightTolerance
        val order = listOf(ResourceKind.GOLD, ResourceKind.ELIXIR, ResourceKind.DARK)
        val rows = mutableListOf<RowCandidate>()

        for (slot in 0..2) {
            val center = firstCenter + pitch * slot
            val slotTop = center - pitch * 0.5f
            val slotBottom = center + pitch * 0.5f
            val candidates = page.lines.mapNotNull { line ->
                val box = line.box ?: return@mapNotNull null
                // Keep the hint near the slot centre and require a value-line
                // height; small clan-name labels also contain digits.
                if (box.centerY < center - pitch * 0.38f ||
                    box.centerY > center + pitch * 0.38f
                ) {
                    return@mapNotNull null
                }
                if (box.height < pitch * 0.28f) return@mapNotNull null
                if (box.right < rightMin || box.right > rightMax) return@mapNotNull null
                if (box.left < columnLeft) return@mapNotNull null
                if (line.text.contains("+")) return@mapNotNull null
                if (isNonLootText(line.text)) return@mapNotNull null
                if (NumberParser.lootCandidates(line.text).isEmpty()) return@mapNotNull null
                line
            }.sortedBy { it.box?.left ?: 0 }

            // Wide loot values can be split into several OCR lines ("1" and
            // "163 088"). Merge adjacent value lines in the same slot before
            // choosing the row, otherwise the leading digit is lost.
            val groups = mutableListOf<MutableList<OcrLine>>()
            for (line in candidates) {
                val box = line.box ?: continue
                val group = groups.lastOrNull()
                val previous = group?.lastOrNull()?.box
                val adjacent = previous != null &&
                    kotlin.math.abs(box.centerY - previous.centerY) <= pitch * 0.35f &&
                    box.left - previous.right <= page.width * 0.03f
                if (adjacent && group != null) {
                    group += line
                } else {
                    groups += mutableListOf(line)
                }
            }
            val hint = groups.mapNotNull { group ->
                val boxes = group.mapNotNull { it.box }
                if (boxes.isEmpty()) return@mapNotNull null
                val text = group.joinToString(" ") { it.text.trim() }
                val digits = NumberParser.lootCandidates(text)
                    .maxOfOrNull { it.digits.length } ?: 0
                if (digits == 0) return@mapNotNull null
                Hint(
                    text = text,
                    box = OcrBox(
                        left = boxes.minOf { it.left },
                        top = boxes.minOf { it.top },
                        right = boxes.maxOf { it.right },
                        bottom = boxes.maxOf { it.bottom }
                    ),
                    digits = digits
                )
            }.maxByOrNull { it.digits * 10_000 + it.box.width }
            if (hint == null) {
                issues += "loot row ${slot + 1}: no OCR line in slot"
            }
            val inset = (pitch * 0.12f).toInt().coerceAtLeast(2)
            // When the full-page reader saw the number, its box is the most
            // accurate crop: it excludes the icon and the panel edge. Slots
            // without a text line fall back to the fixed value column, ending
            // just before the resource icon.
            val box = hint?.box ?: OcrBox(
                left = zeroLeft,
                top = (slotTop + inset).toInt(),
                right = zeroRight,
                bottom = (slotBottom - inset).toInt()
            )
            rows += RowCandidate(
                box = box,
                rawText = hint?.text ?: "",
                role = RowRole.LOOT,
                resource = order[slot]
            )
        }
        return rows
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

    private data class Hint(
        val text: String,
        val box: OcrBox,
        val digits: Int
    )
}
