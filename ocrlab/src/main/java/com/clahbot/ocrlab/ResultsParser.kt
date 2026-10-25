package com.clahbot.ocrlab

import java.util.Locale
import kotlin.math.abs

enum class ResourceKind { GOLD, ELIXIR, DARK }

enum class ResultsOutcome { VICTORY, DEFEAT }

fun interface IconColorSampler {
    fun classify(numberBox: OcrBox): ResourceKind?
}

data class ResultsRead(
    val outcome: ResultsOutcome,
    val stars: Int?,
    val damagePercent: Int?,
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
        damagePercent?.toString() ?: "?",
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
 * A screen is a results screen only when "You got", "Victory"/"Defeat" and
 * "Total damage" are all present. "Return Home" is explicitly not an anchor:
 * the searching screen also has it. Loot rows are assigned to resources by
 * sampling the icon colour to the right of each number, never by row order.
 */
class ResultsScreenParser(
    private val colorSampler: IconColorSampler?
) {

    fun parse(page: OcrPage): ScreenRead {
        val youGot = page.lines.firstOrNull { normalized(it.text).contains("yougot") }
            ?: return ScreenRead.Ignored("no 'You got' anchor")
        val result = page.lines.firstOrNull {
            val text = normalized(it.text)
            text.contains("victory") || text.contains("defeat")
        } ?: return ScreenRead.Ignored("no Victory/Defeat anchor")
        val totalDamage = page.lines.firstOrNull {
            normalized(it.text).contains("totaldamage")
        } ?: return ScreenRead.Ignored("no 'Total damage' anchor")

        val youGotBox = youGot.box
        val resultBox = result.box
        if (youGotBox != null && resultBox != null && resultBox.top >= youGotBox.top) {
            return ScreenRead.Ignored("anchors out of order")
        }

        val issues = mutableListOf<String>()
        val outcome = if (normalized(result.text).contains("defeat")) {
            ResultsOutcome.DEFEAT
        } else {
            ResultsOutcome.VICTORY
        }
        val damage = readDamage(page, totalDamage)
        if (damage == null) issues += "damage missing"

        val loot = assignRows(
            rows = readLootRows(page, youGot, issues),
            colorSampler = colorSampler,
            label = "loot",
            issues = issues
        )
        val bonus = if (outcome == ResultsOutcome.VICTORY) {
            readBonus(page, youGot, colorSampler, issues)
        } else {
            null
        }
        val balances = readBalances(page)

        val missingLoot = listOf(loot.gold, loot.elixir, loot.dark).count { it == null }
        var confidence = 0.55
        if (damage != null) confidence += 0.1
        confidence += 0.1 * (3 - missingLoot)
        if (outcome == ResultsOutcome.DEFEAT) {
            confidence += 0.1
        } else if (bonus != null &&
            bonus.gold != null && bonus.elixir != null && bonus.dark != null
        ) {
            confidence += 0.1
        }
        if (balances.first != null && balances.second != null && balances.third != null) {
            confidence += 0.05
        }
        confidence -= issues.size.coerceAtMost(6) * 0.03
        confidence = confidence.coerceIn(0.05, 1.0)

        return ScreenRead.Results(
            ResultsRead(
                outcome = outcome,
                stars = null,
                damagePercent = damage,
                gold = loot.gold,
                elixir = loot.elixir,
                darkElixir = loot.dark,
                bonusGold = bonus?.gold,
                bonusElixir = bonus?.elixir,
                bonusDarkElixir = bonus?.dark,
                balanceGold = balances.first,
                balanceElixir = balances.second,
                balanceDarkElixir = balances.third,
                confidence = confidence,
                needsReview = missingLoot > 0 || issues.any {
                    it.contains("icon colour unknown") || it.contains("duplicate")
                },
                issues = issues
            )
        )
    }

    private fun readDamage(page: OcrPage, anchor: OcrLine): Int? {
        NumberParser.percent(anchor.text)?.let { return it }
        val anchorY = anchor.box?.centerY ?: return null
        return page.lines.mapNotNull { line ->
            val percent = NumberParser.percent(line.text) ?: return@mapNotNull null
            val y = line.box?.centerY ?: return@mapNotNull null
            Triple(percent, y, abs(y - anchorY))
        }
            .filter { it.third <= page.height * 0.1 }
            .minByOrNull { it.third }
            ?.first
    }

    private fun readLootRows(
        page: OcrPage,
        youGot: OcrLine,
        issues: MutableList<String>
    ): List<Row> {
        val youGotBox = youGot.box ?: return emptyList()
        val troops = page.lines.firstOrNull { normalized(it.text).contains("troops") }
        val top = youGotBox.bottom
        val bottom = troops?.box?.top
            ?.takeIf { it > top }
            ?: (top + page.height / 4).coerceAtMost(page.height)
        val centerX = youGotBox.centerX
        val minX = (centerX - page.width * 0.22).toInt()
        val maxX = (centerX + page.width * 0.15).toInt()

        val lines = page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (box.centerY < top || box.centerY > bottom) return@mapNotNull null
            if (box.centerX < minX || box.centerX > maxX) return@mapNotNull null
            if (line.text.contains("+")) return@mapNotNull null
            if (isNonLootText(line.text)) return@mapNotNull null
            val value = NumberParser.parseLoot(line.text) ?: return@mapNotNull null
            Row(value, box)
        }.sortedBy { it.box.top }

        if (lines.size >= 3) return dedupeRows(lines).take(3)

        // Some OCR engines split one visually spaced number into multiple
        // elements. Group elements by row and rebuild the number.
        val elements = page.lines.flatMap { it.elements }.mapNotNull { element ->
            val box = element.box ?: return@mapNotNull null
            if (box.centerY < top || box.centerY > bottom) return@mapNotNull null
            if (box.centerX < minX || box.centerX > maxX) return@mapNotNull null
            if (element.text.count { it.isDigit() } < 3) return@mapNotNull null
            Row(NumberParser.parse(element.text) ?: return@mapNotNull null, box)
        }
        val grouped = mutableListOf<Row>()
        for (row in elements.sortedBy { it.box.centerY }) {
            val existing = grouped.lastOrNull()
            if (existing != null && abs(existing.box.centerY - row.box.centerY) <
                (row.box.height * 0.8).toInt()
            ) {
                val left = minOf(existing.box.left, row.box.left)
                val topY = minOf(existing.box.top, row.box.top)
                val right = maxOf(existing.box.right, row.box.right)
                val bottomY = maxOf(existing.box.bottom, row.box.bottom)
                grouped[grouped.lastIndex] = Row(
                    value = (existing.value.toString() + row.value.toString()).toLongOrNull()
                        ?: existing.value,
                    box = OcrBox(left, topY, right, bottomY)
                )
            } else {
                grouped += row
            }
        }
        if (grouped.size < 3) issues += "loot rows found ${grouped.size}/3"
        return dedupeRows(grouped).take(3)
    }

    private fun dedupeRows(rows: List<Row>): List<Row> {
        val result = mutableListOf<Row>()
        for (row in rows) {
            if (result.none { abs(it.box.centerY - row.box.centerY) < 12 }) {
                result += row
            }
        }
        return result
    }

    private fun readBonus(
        page: OcrPage,
        youGot: OcrLine,
        colorSampler: IconColorSampler?,
        issues: MutableList<String>
    ): Assigned? {
        val anchor = page.lines.firstOrNull {
            normalized(it.text).contains("bonus")
        } ?: return null
        val anchorBox = anchor.box ?: return null
        val centerX = anchorBox.centerX
        val top = anchorBox.bottom
        val bottom = (youGot.box?.bottom ?: anchorBox.bottom) + page.height / 4
        val rows = page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (box.centerY < top || box.centerY > bottom) return@mapNotNull null
            if (box.centerX < centerX - page.width * 0.12) return@mapNotNull null
            if (isNonLootText(line.text) && !normalized(line.text).contains("bonus")) {
                return@mapNotNull null
            }
            if (normalized(line.text).contains("bonus")) return@mapNotNull null
            val value = NumberParser.parseLoot(line.text) ?: return@mapNotNull null
            Row(value, box)
        }.sortedBy { it.box.top }
        if (rows.size < 3) issues += "bonus rows found ${rows.size}/3"
        return assignRows(rows, colorSampler, "bonus", issues)
    }

    private fun readBalances(page: OcrPage): Triple<Long?, Long?, Long?> {
        val rows = page.lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (box.centerY > page.height * 0.22) return@mapNotNull null
            if (box.centerX < page.width * 0.62) return@mapNotNull null
            if (line.text.count { it.isDigit() } < 4) return@mapNotNull null
            if (normalized(line.text).contains("bonus")) return@mapNotNull null
            val value = NumberParser.parse(line.text) ?: return@mapNotNull null
            value to box.centerY
        }.sortedBy { it.second }
        return if (rows.size >= 3) {
            Triple(rows[0].first, rows[1].first, rows[2].first)
        } else {
            Triple(null, null, null)
        }
    }

    private fun assignRows(
        rows: List<Row>,
        colorSampler: IconColorSampler?,
        label: String,
        issues: MutableList<String>
    ): Assigned {
        var gold: Long? = null
        var elixir: Long? = null
        var dark: Long? = null
        for ((index, row) in rows.withIndex()) {
            val kind = colorSampler?.classify(row.box)
            if (kind == null) {
                issues += "$label row ${index + 1}: icon colour unknown"
                continue
            }
            val existing = when (kind) {
                ResourceKind.GOLD -> gold
                ResourceKind.ELIXIR -> elixir
                ResourceKind.DARK -> dark
            }
            if (existing != null) {
                issues += "$label row ${index + 1}: duplicate ${kind.name.lowercase(Locale.US)}"
                continue
            }
            when (kind) {
                ResourceKind.GOLD -> gold = row.value
                ResourceKind.ELIXIR -> elixir = row.value
                ResourceKind.DARK -> dark = row.value
            }
        }
        return Assigned(gold, elixir, dark)
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

    private data class Row(val value: Long, val box: OcrBox)

    private data class Assigned(
        val gold: Long?,
        val elixir: Long?,
        val dark: Long?
    )
}
