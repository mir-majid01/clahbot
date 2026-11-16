package com.clahbot.walls

import com.clahbot.ocr.NumberParser
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import kotlin.math.abs

/**
 * Turns a captured frame's OCR lines into wall-upgrade candidates from the
 * Buildings & Heroes list (Auto Walls 2.0 PRD §3.2/§3.3).
 *
 * Only real wall titles are walls. A row is anchored on a title that matches
 * `Wall x N` exactly (after OCR normalization); "Wall Ring", "Wallbreaker",
 * "Wall Wrecker" and a bare "Walls" are explicitly excluded. Nothing is ever
 * inferred from a badge, an icon or a price — a trap row with a price looks
 * exactly like a wall row except for its title, so the title is the identity.
 *
 * Rows are visual rectangles, not label-height bands: every OCR line is first
 * clustered by centre-Y into a visual row, and a wall row can only pair the
 * numbers that share its own rectangle. That is what keeps a neighbouring
 * building's badge and price (the 16,000,000 / 25,000,000 defects) out of a
 * wall.
 *
 * Every discovered row carries one of three states:
 *  - IDENTIFIED — title, level, one price and a currency were all read;
 *  - PARTIAL — the wall row was read but its price or currency is missing;
 *  - UNCERTAIN — the level is unreadable or two price-sized numbers compete.
 * The scan report additionally has a list-failed state (header not detected),
 * which is not a row at all. Only IDENTIFIED rows may be selected.
 */
object WallDiscovery {

    data class Config(
        val minLevel: Int = 1,
        val maxLevel: Int = 99,
        /** Wall upgrades are always at least hundreds of coins; smaller numbers are badges/counts. */
        val minPrice: Long = 100L,
        /**
         * Titles that contain "wall" but are not wall upgrade groups. They are
         * listed explicitly (and documented in the class comment) so a future
         * OCR variant cannot silently become a wall; the strict title regex
         * already rejects them, this is the belt-and-braces guard.
         */
        val excludedTitles: List<String> = listOf(
            "wallring", "wallbreaker", "wallwrecker", "walls"
        )
    )

    fun discover(
        lines: List<OcrLine>,
        frameHeight: Int,
        config: Config = Config(),
        /** Reads the currency of the entry's price from its price box (gold/elixir/dark). */
        currencyAt: (OcrBox) -> String? = { null }
    ): List<WallCandidate> {
        if (frameHeight <= 0) return emptyList()
        val rows = wallRowsOf(lines, config)
        return rows.mapIndexedNotNull { index, row ->
            rowCandidate(row, index, config, currencyAt)
        }
    }

    /**
     * The fresh tap box for a candidate the user's priority already selected,
     * re-located on the *current* scan (runtime stage 3). The candidate carries
     * no screen coordinate — only its row identity — so the box is rebuilt from
     * the latest lines and refused if that row no longer matches the candidate's
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
        val fresh = discover(lines, frameHeight, config, currencyAt)
            .getOrNull(candidate.listIndex) ?: return null
        if (fresh.state != WallRowState.IDENTIFIED) return null
        if (fresh.level != candidate.level ||
            fresh.price != candidate.price ||
            fresh.currency != candidate.currency
        ) {
            return null
        }
        return wallRowsOf(lines, config)
            .getOrNull(candidate.listIndex)
            ?.title
            ?.box
    }

    /**
     * A wall row whose identity is incomplete, together with the geometry needed
     * to re-read the weak field from the bitmap.
     */
    data class RowGap(
        val title: String,
        val titleBox: OcrBox,
        val needLevel: Boolean,
        val needPrice: Boolean,
        val needCurrency: Boolean
    )

    /**
     * Rows that were found by title but whose level, price or currency did not
     * read. The device uses this to re-read those fields from an upscaled crop
     * instead of lowering the acceptance gate: a wall row stays UNCERTAIN until
     * its level is actually readable.
     */
    fun incompleteRows(
        lines: List<OcrLine>,
        frameHeight: Int,
        config: Config = Config(),
        currencyAt: (OcrBox) -> String? = { null }
    ): List<RowGap> {
        if (frameHeight <= 0) return emptyList()
        val rows = wallRowsOf(lines, config)
        return rows.mapIndexedNotNull { index, row ->
            val candidate = rowCandidate(row, index, config, currencyAt)
                ?: return@mapIndexedNotNull null
            val titleBox = row.title.box ?: return@mapIndexedNotNull null
            val needLevel = candidate.level <= 0
            val needPrice = candidate.price <= 0L
            val needCurrency = candidate.currency == UNKNOWN_CURRENCY
            if (!needLevel && !needPrice && !needCurrency) return@mapIndexedNotNull null
            RowGap(
                title = candidate.title ?: row.title.text,
                titleBox = titleBox,
                needLevel = needLevel,
                needPrice = needPrice,
                needCurrency = needCurrency
            )
        }
    }

    /**
     * The level badge of a list row: a small number in a dark circle sitting
     * immediately to the LEFT of the row title, vertically centred on it.
     *
     * Sized from the title's own text height rather than the frame, so the same
     * geometry holds on the 1360x612 reference frames and on a 2720x1224 device
     * frame. The region is deliberately a little larger than the badge: the
     * re-read is a full OCR pass over an upscaled crop, and a tight crop clips
     * the digits' anti-aliasing.
     */
    fun levelBadgeRegion(titleBox: OcrBox): OcrBox {
        val h = titleBox.height.coerceAtLeast(8)
        val right = (titleBox.left - (h * 0.2f).toInt()).coerceAtLeast(1)
        val left = (right - (h * 0.95f).toInt()).coerceAtLeast(0)
        val top = (titleBox.centerY - (h * 0.75f).toInt()).coerceAtLeast(0)
        val bottom = titleBox.centerY + (h * 0.75f).toInt()
        return OcrBox(left, top, right, bottom)
    }

    /**
     * The price area of a list row: the numbers to the RIGHT of the title.
     * Wide enough for a grouped price like `2,000,000` at list scale.
     */
    fun priceRegion(titleBox: OcrBox): OcrBox {
        val h = titleBox.height.coerceAtLeast(8)
        val left = (titleBox.right + (h * 0.2f).toInt()).coerceAtLeast(0)
        val right = left + (h * 3.4f).toInt()
        val top = (titleBox.centerY - (h * 0.75f).toInt()).coerceAtLeast(0)
        val bottom = titleBox.centerY + (h * 0.75f).toInt()
        return OcrBox(left, top, right, bottom)
    }

    /**
     * The bottom builder strip of the frame: the row of `free/total` badges and
     * icons under the list. Used for a second, upscaled OCR pass when the first
     * pass read fewer than two badges — a missed left-most badge is what made
     * the list view report the laboratory's `1/1` as the builder count.
     */
    fun builderStripRegion(frameWidth: Int, frameHeight: Int): OcrBox {
        if (frameWidth <= 0 || frameHeight <= 0) return OcrBox(0, 0, 1, 1)
        val left = (frameWidth * STRIP_LEFT_FRACTION).toInt().coerceIn(0, frameWidth - 1)
        val top = (frameHeight * STRIP_TOP_FRACTION).toInt().coerceIn(0, frameHeight - 1)
        return OcrBox(left, top, frameWidth, frameHeight)
    }



    private data class WallRow(val lines: List<OcrLine>, val title: OcrLine)

    /** Every visual row whose title is a real `Wall x N` label. */
    private fun wallRowsOf(lines: List<OcrLine>, config: Config): List<WallRow> {
        val withBox = lines.filter { it.box != null && it.text.isNotBlank() }
        if (withBox.isEmpty()) return emptyList()
        return visualRows(withBox).mapNotNull { row ->
            val titles = row.filter { isWallTitle(it.text, config) }
            // Two wall titles in one visual row are an OCR merge; the row cannot
            // be trusted to belong to either, so it is never a candidate.
            if (titles.size == 1) WallRow(row, titles.single()) else null
        }
    }

    /**
     * Groups OCR lines into visual rows by the gap BETWEEN their boxes.
     *
     * Centre distance is the wrong measure: the level badge, the `Wall x N` title
     * and the price share one list entry but their centres can be a full line
     * height apart, so a centre-anchored rule tears one row into three and the
     * price ends up in a row of its own (the "0 identified, 5 uncertain" panel).
     * Two boxes belong to the same entry when they are separated by less than a
     * fraction of the median line height; adjacent entries have a real gap.
     *
     * The total row height is capped as well, so a chain of boxes that all touch
     * cannot swallow the next entry. The gap is scaled from the frame's own text
     * height, not the frame size, so the same rule holds on the 1360x612
     * reference frames and on a 2720x1224 device frame.
     */
    private fun visualRows(lines: List<OcrLine>): List<List<OcrLine>> {
        val sorted = lines.sortedBy { it.box!!.top }
        if (sorted.isEmpty()) return emptyList()
        val heights = sorted.map { it.box!!.height }.sorted()
        val median = heights[heights.size / 2].toFloat().coerceAtLeast(8f)
        val gap = (median * ROW_EDGE_GAP_FACTOR).coerceAtLeast(8f)
        val maxSpan = (median * MAX_ROW_SPAN_FACTOR).coerceAtLeast(24f)

        val rows = ArrayList<MutableList<OcrLine>>()
        var current = mutableListOf<OcrLine>()
        var rowTop = 0
        var rowBottom = 0
        for (line in sorted) {
            val box = line.box!!
            val fits = current.isNotEmpty() &&
                box.top <= rowBottom + gap &&
                (maxOf(rowBottom, box.bottom) - minOf(rowTop, box.top)) <= maxSpan
            if (current.isEmpty()) {
                rowTop = box.top
                rowBottom = box.bottom
                current += line
            } else if (fits) {
                rowTop = minOf(rowTop, box.top)
                rowBottom = maxOf(rowBottom, box.bottom)
                current += line
            } else {
                rows += current
                current = mutableListOf(line)
                rowTop = box.top
                rowBottom = box.bottom
            }
        }
        if (current.isNotEmpty()) rows += current
        return rows
    }

    /** One wall row → a candidate, or null when the row has no usable geometry. */
    private fun rowCandidate(
        row: WallRow,
        rowIndex: Int,
        config: Config,
        currencyAt: (OcrBox) -> String?
    ): WallCandidate? {
        val titleBox = row.title.box ?: return null
        val numbers = row.lines
            .mapNotNull { line -> NumberParser.parseBalance(line.text)?.let { Num(line, it) } }
            .filter { it.line !== row.title }

        val count = COUNT.find(row.title.text.lowercase())?.groupValues?.get(1)?.toIntOrNull()
        val countFragments = numbers.filter { isCountFragment(it, row.title, count) }
        val pricePool = numbers.filter { n ->
            countFragments.none { it === n } && n.value >= config.minPrice
        }
        val price = pickPrice(pricePool, titleBox)
        val level = pickLevel(numbers, countFragments, price, titleBox, config)
        val currency = price?.line?.box?.let(currencyAt)?.takeIf { it.isNotBlank() }

        // A row with two price-sized numbers is ambiguous: keep the identity but
        // never claim one of the two as the actual upgrade cost.
        val ambiguousPrice = price == null && pricePool.isNotEmpty()
        val state = when {
            level != null && price != null && currency != null -> WallRowState.IDENTIFIED
            level != null && !ambiguousPrice -> WallRowState.PARTIAL
            else -> WallRowState.UNCERTAIN
        }
        return WallCandidate(
            id = buildString {
                append("wall:")
                append(normalize(row.title.text))
                append(":L${level ?: 0}")
                append(":C${count ?: -1}")
                append(":P${price?.value ?: 0L}")
                append(':')
                append(currency ?: "unknown")
                // Unreadable rows have no stable title identity: keep them
                // distinct by position instead of collapsing them together.
                if (level == null) append(":I$rowIndex")
            },
            level = level ?: 0,
            price = price?.value ?: 0L,
            currency = currency ?: "unknown",
            listIndex = rowIndex,
            confidence = when (state) {
                WallRowState.IDENTIFIED -> 0.9f
                WallRowState.PARTIAL -> 0.35f
                WallRowState.UNCERTAIN -> 0.1f
            },
            count = count,
            title = row.title.text.trim(),
            state = state
        )
    }

    /**
     * The actual price line: the one price-sized number in the row. If several
     * price-sized numbers remain (for example a discounted and a struck-through
     * price on the same row) the row is ambiguous and no price is returned.
     */
    private fun pickPrice(pool: List<Num>, titleBox: OcrBox): Num? {
        if (pool.isEmpty()) return null
        if (pool.size == 1) return pool.single()
        val rightOfTitle = pool.filter { (it.line.box?.centerX ?: 0) > titleBox.centerX }
        // Exactly one price-sized number to the right of the title is the price.
        if (rightOfTitle.size == 1) return rightOfTitle.single()
        // Two competing right-hand prices (or none and several elsewhere) are
        // ambiguous. Never guess one of them for the actual upgrade cost.
        return null
    }

    /** The level badge: a 1–2 digit number in the row, never the price or the "x N" count. */
    private fun pickLevel(
        numbers: List<Num>,
        countFragments: List<Num>,
        price: Num?,
        titleBox: OcrBox,
        config: Config
    ): Int? {
        val candidates = numbers.filter { n ->
            n.line !== price?.line &&
                countFragments.none { it === n } &&
                n.value in config.minLevel.toLong()..config.maxLevel.toLong() &&
                n.line.text.count { it.isDigit() } in 1..2
        }
        if (candidates.isEmpty()) return null
        // Real layout: the badge is left of the title. Fall back to the nearest
        // number for frames where the badge is stacked above the title.
        val left = candidates.filter { (it.line.box?.centerX ?: 0) < titleBox.centerX }
        val pool = left.ifEmpty { candidates }
        val sorted = pool.sortedBy {
            abs((it.line.box?.centerY ?: 0) - titleBox.centerY)
        }
        val best = sorted.first()
        val bestDistance = abs((best.line.box?.centerY ?: 0) - titleBox.centerY)
        val rival = sorted.drop(1).firstOrNull {
            it.value != best.value &&
                abs((it.line.box?.centerY ?: 0) - titleBox.centerY) == bestDistance
        }
        return if (rival == null) best.value.toInt() else null
    }

    /**
     * A number that is actually the "x N" wall count rather than a price. The
     * count is normally inside the title, but OCR sometimes emits it as a
     * separate line to the right of "Wall x".
     */
    private fun isCountFragment(n: Num, title: OcrLine, count: Int?): Boolean {
        val box = n.line.box ?: return false
        val titleBox = title.box ?: return false
        if (count != null && n.value == count.toLong() && box.centerX >= titleBox.centerX) {
            return true
        }
        val text = normalize(title.text)
        return (text.endsWith("x") || text.endsWith("×")) &&
            box.centerX >= titleBox.right &&
            n.value <= 9_999L
    }

    /**
     * True only for a real wall-group title: `Wall x N`, tolerant of OCR
     * confusions in the word "Wall" (Wal1, WaII, Wail). Everything else that
     * happens to contain "wall" — a ring upgrade, a wall-breaker troop, the
     * bare word "Walls" — is excluded before the regex is even tried.
     */
    private fun isWallTitle(text: String, config: Config): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        if (config.excludedTitles.any { normalized.contains(it) }) return false
        return WALL_TITLE.matches(normalized)
    }

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * `Wall x N` with OCR tolerance in the word Wall only:
     * `wallx317`, `wal1x12`, `waiix4`, `wailx3`. The count must be present and
     * numeric; a title that lost its number is not a wall row.
     */
    private val WALL_TITLE = Regex("^wa[l1i]{1,3}x(\\d{1,4})$")

    /** The "x 317" part of a "Wall x 317" label: how many walls sit in the group. */
    private val COUNT = Regex("[x×]\\s*(\\d{1,4})")

    /** Box-to-box gap, as a multiple of the frame's median line height, that still belongs to one row. */
    private const val ROW_EDGE_GAP_FACTOR = 0.5f

    /** Hard cap on one visual row's height, so touching boxes cannot chain across entries. */
    private const val MAX_ROW_SPAN_FACTOR = 4f

    /** Builder strip starts this far across the frame (right-edge HUD). */
    private const val STRIP_LEFT_FRACTION = 0.70f

    /** ...and this far down; measured at 0.71-0.78 on the reference frames. */
    private const val STRIP_TOP_FRACTION = 0.68f

    /** Currency value used when the price icon could not be read. */
    private const val UNKNOWN_CURRENCY = "unknown"

    private class Num(val line: OcrLine, val value: Long)
}
