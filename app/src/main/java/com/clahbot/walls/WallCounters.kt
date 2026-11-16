package com.clahbot.walls

import com.clahbot.ocr.NumberParser
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.ResourceKind

/**
 * Finds the live gold and elixir counters in their dedicated top strip.
 *
 * A counter is accepted only when all three facts agree:
 *  - it is in the counter strip (top of the village/list screen);
 *  - its icon classifies as gold/elixir/dark (the caller supplies the sampler);
 *  - it sits in the left-most icon-classified numeric column. The build panel
 *    puts upgrade prices in a column to the right of the counters, so scanning
 *    every number in the strip in OCR order (the old behaviour) could pair a
 *    panel price with the gold icon and report 16,000,000 or 25,000,000.
 *
 * Two-digit noise (the 90 / 56 readings in the report) and any other
 * implausible value are rejected rather than shown. When the current frame has
 * no valid reading, the caller may pass the last [Reading] so the previous
 * value is kept with an explicit stale flag — never silently presented as live.
 */
object WallCounters {

    data class Reading(
        val gold: Long? = null,
        val elixir: Long? = null,
        /** Numbers that looked like counters, whether or not an icon matched. */
        val rows: Int = 0,
        val classified: Int = 0,
        /** The gold value is carried over from an earlier frame. */
        val goldStale: Boolean = false,
        /** The elixir value is carried over from an earlier frame. */
        val elixirStale: Boolean = false
    ) {
        val anything: Boolean get() = gold != null || elixir != null
        val stale: Boolean get() = goldStale || elixirStale

        /** Status text for the Walls tab, so a bad read is visible, not silent. */
        fun describe(): String = when {
            gold != null && elixir != null && stale ->
                "gold ${compact(gold)}${staleMark(goldStale)} · " +
                    "elixir ${compact(elixir)}${staleMark(elixirStale)}"
            gold != null && elixir != null -> "gold ${compact(gold)} · elixir ${compact(elixir)}"
            gold != null -> "gold ${compact(gold)}${staleMark(goldStale)} · elixir not read"
            elixir != null ->
                "elixir ${compact(elixir)}${staleMark(elixirStale)} · gold not read"
            rows > 0 -> "counters unreadable"
            else -> "no counters on screen"
        }

        private fun staleMark(isStale: Boolean) = if (isStale) " (stale)" else ""

        private fun compact(value: Long): String = when {
            value >= 1_000_000L -> "${value / 1_000_000}M"
            value >= 1_000L -> "${value / 1_000}K"
            else -> value.toString()
        }
    }

    /**
     * Reads the counters of one frame.
     *
     * @param previous the last valid reading, used only to keep a previously
     *   read value with its stale flag when this frame cannot read that
     *   resource. Never upgrades a stale value to a live one.
     */
    fun read(
        lines: List<OcrLine>,
        frameWidth: Int,
        frameHeight: Int,
        classify: (OcrBox) -> ResourceKind?,
        previous: Reading? = null
    ): Reading {
        if (frameWidth <= 0 || frameHeight <= 0) return Reading()
        val candidates = ArrayList<Candidate>()
        for (line in lines) {
            val box = line.box ?: continue
            if (box.centerY > frameHeight * TOP_LIMIT) continue
            if (box.centerX < frameWidth * LEFT_LIMIT) continue
            val value = NumberParser.parseBalance(line.text) ?: continue
            candidates += Candidate(box, value, classify(box))
        }
        if (candidates.isEmpty()) return carry(Reading(), previous)

        val column = leftMostIconColumn(candidates, frameWidth)
        val byKind = column
            .filter { it.value in MIN_PLAUSIBLE..MAX_PLAUSIBLE }
            .groupBy { it.kind }
        val gold = byKind[ResourceKind.GOLD]?.minByOrNull { it.box.centerY }?.value
        val elixir = byKind[ResourceKind.ELIXIR]?.minByOrNull { it.box.centerY }?.value
        val current = Reading(
            gold = gold,
            elixir = elixir,
            rows = candidates.size,
            classified = column.count { it.kind == ResourceKind.GOLD || it.kind == ResourceKind.ELIXIR }
        )
        return carry(current, previous)
    }

    /**
     * Folds several readings of the SAME unchanged screen into one answer.
     *
     * A single capture can mis-read a counter (a flying banner, a gem icon over a
     * digit), so a value is accepted only when at least two of the samples agree.
     * One lonely reading is not an agreement: the currency is left unread (and
     * may still be carried forward with its stale marker by [carry]).
     *
     * Builders use the same 2-of-3 rule in [BuilderDetection.majority]; this is
     * the balance counterpart so both halves of the report are equally strict.
     */
    fun agree(readings: List<Reading>, previous: Reading? = null): Reading {
        if (readings.isEmpty()) return carry(Reading(), previous)
        if (readings.size == 1) return carry(readings.single(), previous)
        val gold = agreeValue(readings) { it.gold }
        val elixir = agreeValue(readings) { it.elixir }
        val current = Reading(
            gold = gold,
            elixir = elixir,
            rows = readings.maxOfOrNull { it.rows } ?: 0,
            classified = readings.maxOfOrNull { it.classified } ?: 0
        )
        return carry(current, previous)
    }

    /** The value at least two samples share, or null when no value does. */
    private fun agreeValue(readings: List<Reading>, pick: (Reading) -> Long?): Long? =
        readings.mapNotNull(pick)
            .groupingBy { it }
            .eachCount()
            .entries
            .filter { it.value >= 2 }
            .maxByOrNull { it.value }
            ?.key

    /**
     * The numeric column that actually carries a resource icon, choosing the
     * left-most such column. Panel prices are further right and are therefore
     * excluded. Unclassified numbers do not create a column of their own.
     */
    private fun leftMostIconColumn(
        candidates: List<Candidate>,
        frameWidth: Int
    ): List<Candidate> {
        val classified = candidates.filter { it.kind != null }
        if (classified.isEmpty()) return emptyList()
        val sorted = classified.sortedBy { it.box.centerX }
        val columns = ArrayList<MutableList<Candidate>>()
        var column = ArrayList<Candidate>()
        var anchor = sorted.first().box.centerX
        for (candidate in sorted) {
            if (candidate.box.centerX - anchor > frameWidth * COLUMN_GAP) {
                if (column.isNotEmpty()) columns += column
                column = ArrayList()
                anchor = candidate.box.centerX
            }
            column += candidate
        }
        if (column.isNotEmpty()) columns += column
        return columns.firstOrNull().orEmpty()
    }

    /** Keeps a previous value only with a stale marker; never invents one. */
    private fun carry(current: Reading, previous: Reading?): Reading {
        if (previous == null) return current
        val gold = current.gold ?: previous.gold?.takeIf { it in MIN_PLAUSIBLE..MAX_PLAUSIBLE }
        val elixir = current.elixir ?: previous.elixir?.takeIf { it in MIN_PLAUSIBLE..MAX_PLAUSIBLE }
        return current.copy(
            gold = gold,
            elixir = elixir,
            goldStale = current.gold == null && gold != null,
            elixirStale = current.elixir == null && elixir != null
        )
    }

    private class Candidate(val box: OcrBox, val value: Long, val kind: ResourceKind?)

    /** The counter strip lives in the top quarter of the village. */
    private const val TOP_LIMIT = 0.26f

    /**
     * 40 % rather than the old 58 %: with the build panel open the gold number
     * starts around 52 % of the width, and its digits begin further left.
     */
    private const val LEFT_LIMIT = 0.40f

    /** A different column starts when the gap exceeds this fraction of width. */
    private const val COLUMN_GAP = 0.08f

    /**
     * Clash counters are always at least three digits on a real village; the
     * 90 / 56 readings in the bug report are OCR noise, not balances.
     */
    const val MIN_PLAUSIBLE = 100L

    /** A single counter can never exceed this; larger reads are OCR garbage. */
    const val MAX_PLAUSIBLE = 999_999_999L
}
