package com.clahbot.ocrlab

/**
 * Numbers on Clash result screens use space separators ("1 200 960"), NBSP,
 * commas, and a leading "+" on bonuses. A failed read is null, never zero.
 */
object NumberParser {

    fun parse(raw: String): Long? {
        val digits = buildString {
            for (char in raw) {
                if (char.isDigit()) append(char)
            }
        }
        if (digits.isEmpty()) return null
        return digits.toLongOrNull()
    }

    /** Loot/bonus rows need at least three digits; levels and labels are ignored. */
    fun parseLoot(raw: String): Long? {
        if (raw.count { it.isDigit() } < 3) return null
        return parse(raw)
    }

    fun percent(raw: String): Int? {
        val match = PERCENT.find(raw) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        return value.takeIf { it in 0..100 }
    }

    private val PERCENT = Regex("""(\d{1,3})\s*%""")
}
