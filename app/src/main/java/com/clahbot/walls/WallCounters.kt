package com.clahbot.walls

import com.clahbot.ocr.NumberParser
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.ResourceKind

/**
 * Finds the live gold and elixir counters by the icon beside the digits.
 *
 * Neither row order nor a fixed box works on the real game: the build panel
 * slides the whole counter strip left (the user's own screenshots show the gold
 * number at 52 % width with it open, 85 % without), and a village with no dark
 * elixir has one row fewer than the parser expects. The icon is the only
 * stable fact, and the caller supplies the classifier so this stays testable.
 */
object WallCounters {

    data class Reading(
        val gold: Long? = null,
        val elixir: Long? = null,
        /** Numbers that looked like counters, whether or not an icon matched. */
        val rows: Int = 0,
        val classified: Int = 0
    ) {
        val anything: Boolean get() = gold != null || elixir != null

        /** Status text for the Walls tab, so a bad read is visible, not silent. */
        fun describe(): String = when {
            gold != null && elixir != null -> "gold ${compact(gold)} · elixir ${compact(elixir)}"
            gold != null -> "gold ${compact(gold)} · elixir not read"
            elixir != null -> "elixir ${compact(elixir)} · gold not read"
            rows > 0 -> "counters unreadable"
            else -> "no counters on screen"
        }

        private fun compact(value: Long): String = when {
            value >= 1_000_000L -> "${value / 1_000_000}M"
            value >= 1_000L -> "${value / 1_000}K"
            else -> value.toString()
        }
    }

    fun read(
        lines: List<OcrLine>,
        frameWidth: Int,
        frameHeight: Int,
        classify: (OcrBox) -> ResourceKind?
    ): Reading {
        if (frameWidth <= 0 || frameHeight <= 0) return Reading()
        var gold: Long? = null
        var elixir: Long? = null
        var rows = 0
        var classified = 0
        for (line in lines) {
            val box = line.box ?: continue
            if (box.centerY > frameHeight * TOP_LIMIT) continue
            if (box.centerX < frameWidth * LEFT_LIMIT) continue
            val value = NumberParser.parseBalance(line.text) ?: continue
            rows++
            when (classify(box)) {
                ResourceKind.GOLD -> {
                    classified++
                    if (gold == null) gold = value
                }

                ResourceKind.ELIXIR -> {
                    classified++
                    if (elixir == null) elixir = value
                }

                else -> Unit
            }
        }
        return Reading(gold = gold, elixir = elixir, rows = rows, classified = classified)
    }

    /** The counter strip lives in the top quarter of the village. */
    private const val TOP_LIMIT = 0.26f

    /**
     * 40 % rather than the old 58 %: with the build panel open the gold number
     * starts around 52 % of the width, and its digits begin further left.
     */
    private const val LEFT_LIMIT = 0.40f
}
