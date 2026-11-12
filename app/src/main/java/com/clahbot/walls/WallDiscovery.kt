package com.clahbot.walls

import com.clahbot.ocr.NumberParser
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine

/**
 * Turns a captured frame's OCR lines into wall-upgrade candidates from the
 * Buildings & Heroes list (Auto Walls 2.0 PRD §3.2/§3.3).
 *
 * It is coordinate-independent: rows are grouped by their vertical gaps on the
 * frame being read, so a wall is found wherever it currently is and re-read on
 * every capture (scroll → new capture → new rows). It never assumes a fixed
 * position. Rows that are not clearly walls, or that lack a readable
 * level/price/currency, come back with low confidence so [WallSelection] drops
 * them instead of tapping something uncertain.
 */
object WallDiscovery {

    data class Config(
        /** Normalized substrings that mark a wall row (the list label). */
        val wallTokens: List<String> = listOf("wall"),
        /** Lines within this fraction of frame height of the row are one entry. */
        val rowGap: Float = 0.02f,
        val minLevel: Int = 1,
        val maxLevel: Int = 99
    )

    fun discover(
        lines: List<OcrLine>,
        frameHeight: Int,
        config: Config = Config(),
        /** Reads the currency of the entry's price from its price box (gold/elixir/dark). */
        currencyAt: (OcrBox) -> String? = { null }
    ): List<WallCandidate> {
        if (frameHeight <= 0) return emptyList()
        val withBox = lines.filter { it.box != null && it.text.isNotBlank() }
        if (withBox.isEmpty()) return emptyList()

        val rows = groupRows(withBox, frameHeight, config.rowGap)
        return rows.mapIndexedNotNull { rowIndex, row ->
            rowCandidate(row, rowIndex, config, currencyAt)
        }
    }

    /**
     * The fresh tap box for a candidate the user's priority already selected,
     * re-located on the *current* scan (runtime stage 3). The candidate carries
     * no coordinate — only its row identity — so the box is rebuilt from the
     * latest lines and refused if that row no longer matches the candidate's
     * level/price/currency. A scroll, list change or stale index therefore never
     * taps the wrong wall; it returns null and the caller re-scans.
     */
    fun tapBox(
        candidate: WallCandidate,
        lines: List<OcrLine>,
        frameHeight: Int,
        config: Config = Config(),
        currencyAt: (OcrBox) -> String? = { null }
    ): OcrBox? {
        if (frameHeight <= 0) return null
        val withBox = lines.filter { it.box != null && it.text.isNotBlank() }
        if (withBox.isEmpty()) return null
        val rows = groupRows(withBox, frameHeight, config.rowGap)
        val row = rows.getOrNull(candidate.listIndex) ?: return null
        val fresh = rowCandidate(row, candidate.listIndex, config, currencyAt) ?: return null
        if (fresh.level != candidate.level ||
            fresh.price != candidate.price ||
            fresh.currency != candidate.currency
        ) {
            return null
        }
        return nameLine(row, config)?.box
    }

    /** One list row → a candidate, or null when the row is not a wall entry. */
    private fun rowCandidate(
        row: List<OcrLine>,
        rowIndex: Int,
        config: Config,
        currencyAt: (OcrBox) -> String?
    ): WallCandidate? {
        val nameLine = nameLine(row, config) ?: return null
        val numbers = row.mapNotNull { l ->
            NumberParser.parseBalance(l.text)?.let { Num(l, it) }
        }
        // Price is the largest number that is NOT the "Wall x <count>" label.
        val price = numbers.filter { it.line !== nameLine }
            .maxByOrNull { it.value }
            ?.takeIf { it.value > 0L }
        // Level is the small badge number — never the "x <count>" on the label.
        val level = numbers.firstOrNull { n ->
            val digits = n.line.text.count { it.isDigit() }
            n.line !== nameLine && n.line !== price?.line &&
                n.value in config.minLevel.toLong()..config.maxLevel.toLong() &&
                digits in 1..2 &&
                (price == null || n.value != price.value)
        }?.value?.toInt()
        val currency = price?.line?.box?.let(currencyAt)
        val count = COUNT.find(nameLine.text.lowercase())?.groupValues?.get(1)?.toIntOrNull()

        val complete = level != null && price != null && !currency.isNullOrBlank()
        return WallCandidate(
            id = "row:$rowIndex",
            level = level ?: 0,
            price = price?.value ?: 0L,
            currency = currency ?: "unknown",
            listIndex = rowIndex,
            confidence = if (complete) 0.9f else 0.2f,
            count = count
        )
    }

    /** The wall label line of a row ("Wall" / "Wall x N"), or null for non-walls. */
    private fun nameLine(row: List<OcrLine>, config: Config): OcrLine? =
        row.firstOrNull { line ->
            val text = normalize(line.text)
            config.wallTokens.any { text.contains(it) }
        }

    /** Groups lines into list entries by vertical gaps; order is preserved top→bottom. */
    private fun groupRows(lines: List<OcrLine>, frameHeight: Int, rowGap: Float): List<List<OcrLine>> {
        val gapPx = (frameHeight * rowGap).coerceAtLeast(1f)
        val sorted = lines.sortedBy { it.box!!.top }
        val rows = mutableListOf<MutableList<OcrLine>>()
        var currentBottom = Int.MIN_VALUE
        for (line in sorted) {
            val box = line.box!!
            if (rows.isEmpty() || box.top > currentBottom + gapPx) {
                rows += mutableListOf(line)
                currentBottom = box.bottom
            } else {
                rows.last() += line
                if (box.bottom > currentBottom) currentBottom = box.bottom
            }
        }
        return rows
    }

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** The "x 317" part of a "Wall x 317" label: how many walls sit in the group. */
    private val COUNT = Regex("[x×]\\s*(\\d{1,4})")

    private class Num(val line: OcrLine, val value: Long)
}
