package com.clahbot.ocrlab

/**
 * Numeric validation for OCR output.
 *
 * The game groups loot and balance values by three digits ("1 200 960").
 * Bonus-panel values are ungrouped and prefixed with "+" ("+370000").
 * Letters that ML Kit commonly confuses with digits are corrected, but only
 * when every remaining character is valid for the field. A token that does not
 * match the expected format is rejected (null), never guessed.
 */
object NumberParser {

    private val CONFUSIONS = mapOf(
        'O' to '0', 'o' to '0',
        'S' to '5', 's' to '5',
        'G' to '6', 'g' to '6',
        'B' to '8', 'b' to '8',
        'I' to '1', 'i' to '1', 'l' to '1', '|' to '1',
        'Z' to '2', 'z' to '2'
    )

    /** One to three digits, then zero or more groups of exactly three digits. */
    private val GROUPED = Regex("""^\d{1,3}(?: \d{3})*$""")
    private val BONUS = Regex("""^\d{3,7}$""")

    /**
     * Returns a digit string with spaces as grouping, or null if the token
     * contains any character that is not a digit, a known confusion, a comma,
     * whitespace, or (for bonus) a leading "+" / bullet.
     */
    fun mapNumeric(raw: String, allowBonusPrefix: Boolean = false): String? {
        val text = raw.replace('\u00A0', ' ').trim()
        if (text.isEmpty()) return null
        val output = StringBuilder()
        var seenDigit = false
        var prefixUsed = false
        for (char in text) {
            when {
                char.isDigit() -> {
                    output.append(char)
                    seenDigit = true
                }

                char == ' ' || char == ',' -> {
                    if (output.isNotEmpty() && output.last() != ' ') output.append(' ')
                }

                (char == '+' || char == '•') && allowBonusPrefix &&
                    !seenDigit && !prefixUsed -> {
                    prefixUsed = true
                }

                CONFUSIONS.containsKey(char) -> {
                    output.append(CONFUSIONS.getValue(char))
                    seenDigit = true
                }

                else -> return null
            }
        }
        return output.toString().trim().replace(Regex(" {2,}"), " ")
    }

    fun isNumericLike(raw: String, allowBonusPrefix: Boolean = false): Boolean {
        val mapped = mapNumeric(raw, allowBonusPrefix) ?: return false
        return mapped.count { it.isDigit() } >= 3
    }

    /** Loot/balance value; the OCR text must still contain the digit grouping. */
    fun parseGrouped(raw: String): Long? {
        val mapped = mapNumeric(raw, allowBonusPrefix = false) ?: return null
        if (!GROUPED.matches(mapped)) return null
        return mapped.replace(" ", "").toLongOrNull()
    }

    /** Bonus-panel value: optional "+"/bullet, ungrouped 3-7 digits. */
    fun parseBonus(raw: String): Long? {
        val mapped = mapNumeric(raw, allowBonusPrefix = true) ?: return null
        val digits = mapped.replace(" ", "")
        if (!BONUS.matches(digits)) return null
        return digits.toLongOrNull()
    }
}
