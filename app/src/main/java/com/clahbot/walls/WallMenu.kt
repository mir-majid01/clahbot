package com.clahbot.walls

import com.clahbot.ocr.OcrLine
import kotlin.math.abs

/**
 * Controlled top-to-bottom scanning of the Buildings & Heroes list.
 *
 * The scanner must never scroll blindly: the list is only scanned when the
 * `Buildings & Heroes` header is actually detected on the captured frame.
 * Each viewport is read independently by [WallDiscovery] (so a row's price can
 * only come from that same row), then [merge] folds the viewports together.
 *
 * Header reading is tolerant but never blind: the title may arrive on one OCR
 * line or split into a `Buildings` line and a `Heroes` line in the same band,
 * and OCR variants of "Buildings" are accepted. Only the top of the frame is
 * considered, and every failure carries the raw top-band text in
 * [State.headerDetail] so the scanner can log which check failed instead of
 * silently retrying.
 *
 * Overlapping viewports repeat rows. A row group is identified by its title,
 * level, count, price and currency; when the same group is seen twice the
 * higher confidence reading wins and the original list position is kept. A
 * viewport that adds no new row means the list did not move — the bottom (or a
 * failed scroll) — so the loop stops instead of capturing forever.
 */
object WallMenu {

    /** Hard cap on captured viewports; a long list still finishes. */
    const val MAX_VIEWPORTS = 12

    /**
     * How many viewports in a row may be read without a single wall row before
     * the scan stops.
     *
     * Removed from the bottom rule on purpose: the scan used to stop when the
     * first viewport held no wall, which is why a list that starts with
     * suggested upgrades never scrolled to its real bottom. The bottom is now
     * detected only by two consecutive scrolls that leave the list signature
     * unchanged ([com.clahbot.walls.AutoWallsScanner]), so a wall-free top
     * still scrolls. Kept as the diagnostic threshold logged per viewport.
     */
    const val EMPTY_VIEWPORT_WARNING = 2

    data class State(
        val headerDetected: Boolean,
        val scanEnabled: Boolean,
        /** Why scanning is refused, or null when it is allowed. */
        val reason: String? = null,
        /** First pixel row below the header, where list rows start. */
        val listTop: Int = 0,
        /** Last pixel row that belongs to the list. */
        val listBottom: Int = 0,
        /**
         * Raw text of the top band that was inspected for the header. The
         * scanner logs this on every failed attempt so a refusal can be
         * explained without guessing.
         */
        val headerDetail: String = ""
    )

    /** Inspects a captured frame for the list header. */
    fun inspect(lines: List<OcrLine>, frameHeight: Int): State {
        val limit = if (frameHeight > 0) {
            (frameHeight * HEADER_TOP_FRACTION).toInt().coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
        val top = lines
            .filter { it.box != null && it.text.isNotBlank() && it.box!!.centerY <= limit }
            .sortedBy { it.box!!.centerY }
        val detail = top.map { it.text.trim().take(24) }.take(12)
            .joinToString(" | ")
            .ifEmpty { "(no text in the top band)" }

        val heights = top.map { it.box!!.height }.sorted()
        val medianHeight = if (heights.isEmpty()) 24f else heights[heights.size / 2].toFloat()
        val bandGap = (medianHeight * HEADER_LINE_GAP).coerceAtLeast(36f)

        val single = top
            .filter { isBuildings(it.text) && isHeroes(it.text) }
            .minByOrNull { it.box!!.centerY }

        val header: List<OcrLine> = if (single != null) {
            listOf(single)
        } else {
            // The header can be OCR'd as two words on two lines ("Buildings"
            // and "Heroes" tabs). They must sit in the same band; a "Heroes"
            // heading elsewhere on the screen is not the list header.
            val buildings = top.firstOrNull { isBuildings(it.text) && !isHeroes(it.text) }
            val heroes = buildings?.let { b ->
                top.firstOrNull {
                    it !== b &&
                        isHeroes(it.text) &&
                        abs(it.box!!.centerY - b.box!!.centerY) <= bandGap
                }
            }
            if (buildings != null && heroes != null) listOf(buildings, heroes) else emptyList()
        }

        if (header.isEmpty()) {
            // Fallback: a strict `Wall x N` title is unambiguous proof that the
            // Buildings & Heroes list is open. The header itself is sometimes
            // missed — the tabs can be OCR'd as a single unreadable line, or the
            // "Heroes" tab sits under an icon — and the device log recorded a
            // frame with `Wall x 254` plainly visible that was still refused as
            // "list not detected". A wall title cannot appear anywhere else, so
            // it is a safe anchor; the row title is now the header.
            val wallTitle = top
                .firstOrNull { WALL_TITLE.matches(normalize(it.text)) }
                ?: lines.firstOrNull { it.box != null && WALL_TITLE.matches(normalize(it.text)) }
            if (wallTitle != null) {
                return State(
                    headerDetected = true,
                    scanEnabled = true,
                    listTop = (wallTitle.box!!.top - wallTitle.box!!.height).coerceAtLeast(0),
                    listBottom = frameHeight.coerceAtLeast(wallTitle.box!!.bottom),
                    headerDetail = "header missed; anchored on wall title " +
                        "\"${wallTitle.text.trim()}\" at y=${wallTitle.box!!.top} from [$detail]"
                )
            }
            return State(
                headerDetected = false,
                scanEnabled = false,
                reason = "Buildings & Heroes list not detected - open the wall list, " +
                    "then tap Scan screen again",
                headerDetail = detail
            )
        }
        val top1 = header.minOf { it.box!!.top }.coerceAtLeast(0)
        val bottom = header.maxOf { it.box!!.bottom }
        return State(
            headerDetected = true,
            scanEnabled = true,
            listTop = bottom.coerceAtLeast(0),
            listBottom = frameHeight.coerceAtLeast(bottom),
            headerDetail = "matched at y=$top1..$bottom from [$detail]"
        )
    }

    data class Merge(
        /** Every distinct wall row seen so far, in list order. */
        val rows: List<WallCandidate>,
        /** Rows this viewport added that were not already known. */
        val added: Int,
        /** No new row appeared: the list is at its end or did not move. */
        val atBottom: Boolean
    )

    /**
     * Folds one viewport's rows into the rows already collected.
     *
     * Duplicate removal is by wall-group identity (title + level + count +
     * price + currency), not by screen position: positions change with every
     * scroll, the identity does not.
     */
    fun merge(existing: List<WallCandidate>, incoming: List<WallCandidate>): Merge {
        val rows = existing.toMutableList()
        var added = 0
        for (candidate in incoming) {
            val index = rows.indexOfFirst { sameGroup(it, candidate) }
            if (index < 0) {
                rows += candidate.copy(listIndex = rows.size)
                added++
            } else if (candidate.confidence > rows[index].confidence) {
                // Keep the row's original list position; use the clearer read.
                rows[index] = candidate.copy(listIndex = rows[index].listIndex)
            }
        }
        return Merge(
            rows = rows,
            added = added,
            atBottom = existing.isNotEmpty() && added == 0
        )
    }

    /**
     * True when both candidates describe the same wall group. An unreadable
     * level is never merged (two unknown rows could be different walls), and a
     * missing count on ONE side is tolerated because OCR often drops it — but
     * when both sides lack the count the rows stay distinct: two count-less
     * rows at the same level and price can genuinely be two different walls,
     * and merging them collapsed the scan into a false "bottom reached".
     */
    private fun sameGroup(a: WallCandidate, b: WallCandidate): Boolean {
        if (a.level <= 0 || b.level <= 0) return a.id == b.id
        if (a.level != b.level) return false
        // An unreadable currency is not a different currency: a partial row must
        // still fold into the identified reading of the same group, otherwise an
        // overlapping viewport reports the same wall twice.
        if (a.currency != b.currency &&
            a.currency != UNKNOWN_CURRENCY && b.currency != UNKNOWN_CURRENCY
        ) {
            return false
        }
        val aTitle = a.title?.let { normalize(it) }
        val bTitle = b.title?.let { normalize(it) }
        if (aTitle != null && bTitle != null && aTitle != bTitle) return false
        // A partial row (price 0) may merge into the identified reading of the
        // same group, but two different known prices are different rows.
        if (a.price > 0L && b.price > 0L && a.price != b.price) return false
        if (a.count == null && b.count == null) return false
        return a.count == b.count || a.count == null || b.count == null
    }

    private fun isBuildings(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.contains("building") ||
            normalized.contains("bulding") ||
            normalized.contains("buildng")
    }

    private fun isHeroes(text: String): Boolean = normalize(text).contains("hero")

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The same strict `Wall x N` identity [WallDiscovery] uses. Duplicated here
     * on purpose: [WallMenu] must be able to recognise a wall title without
     * depending on the discovery pass, and both must stay in step.
     */
    private val WALL_TITLE = Regex("^wa[l1i]{1,3}x(\\d{1,4})$")

    /**
     * Ordered list-content signature of one frame: the normalized text of every
     * OCR line below the `Buildings & Heroes` header (or of the whole frame
     * when the header is not visible). Two frames captured after a scroll
     * attempt with equal signatures show the same content at the same positions
     * — the list did not move, which is the only honest "bottom" signal.
     */
    fun listSignature(lines: List<OcrLine>, frameHeight: Int): List<String> {
        val listTop = inspect(lines, frameHeight)
            .takeIf { it.headerDetected }
            ?.listTop
            ?.takeIf { it > 0 }
        return lines.mapNotNull { line ->
            val box = line.box
            if (listTop != null && (box == null || box.top < listTop)) return@mapNotNull null
            normalize(line.text)
        }.filter { it.isNotEmpty() }
    }

    /** True when two frames' list content is identical (the list did not move). */
    fun framesUnchanged(previous: List<String>, next: List<String>): Boolean =
        previous == next && previous.isNotEmpty()

    /**
     * Only the top of the frame can hold the list header. The home HUD, the
     * bottom builder strip and the list rows themselves are never candidates.
     */
    private const val HEADER_TOP_FRACTION = 0.30f

    /** Two split header lines must sit within this multiple of the median line height. */
    private const val HEADER_LINE_GAP = 2.5f

    /** Currency value used when the price (and therefore the icon) was unreadable. */
    private const val UNKNOWN_CURRENCY = "unknown"
}
