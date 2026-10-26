package com.clahbot.ocr

/**
 * Digits-only numeric parsing.
 *
 * The game's OCR often loses grouping spaces, substitutes letters that look
 * like digits, clips a leading digit, or leaves label text on the same line.
 * Parsing therefore treats grouping as optional, splits label text from the
 * number, and expands confusable letters to every plausible digit. Every
 * plausible digit string is returned; choosing one is the consensus layer's
 * job, never a single guess.
 */
object NumberParser {

    data class Candidate(val value: Long, val digits: String)

    private val DIGIT_ALTERNATIVES: Map<Char, CharArray> = mapOf(
        'O' to charArrayOf('0'), 'o' to charArrayOf('0'),
        'Q' to charArrayOf('0'), 'D' to charArrayOf('0'),
        'I' to charArrayOf('1'), 'i' to charArrayOf('1'),
        'l' to charArrayOf('1'), '|' to charArrayOf('1'), '!' to charArrayOf('1'),
        'Z' to charArrayOf('2'), 'z' to charArrayOf('2'),
        'S' to charArrayOf('5'), 's' to charArrayOf('5'),
        'G' to charArrayOf('6'),
        'g' to charArrayOf('9'), 'q' to charArrayOf('9'),
        'B' to charArrayOf('8'),
        'b' to charArrayOf('6', '8'),
        'A' to charArrayOf('4'),
        'T' to charArrayOf('7')
    )

    private val SEPARATORS = setOf(
        ' ', '\u00A0', '\u202F', '\u2009', ',', '\'', '_'
    )
    private val PREFIXES = setOf('+', '•', '·', '∙', '#', '^', '~')

    private const val MAX_DIGITS = 12
    private const val MAX_CANDIDATES = 64

    fun lootCandidates(raw: String): List<Candidate> =
        candidates(raw, minDigits = 3, maxDigits = MAX_DIGITS)

    fun balanceCandidates(raw: String): List<Candidate> =
        candidates(raw, minDigits = 3, maxDigits = MAX_DIGITS)

    fun bonusCandidates(raw: String): List<Candidate> =
        candidates(raw, minDigits = 3, maxDigits = 8)

    fun searchCostCandidates(raw: String): List<Candidate> =
        candidates(raw, minDigits = 3, maxDigits = 7)

    fun parseLoot(raw: String): Long? = single(lootCandidates(raw))

    fun parseBalance(raw: String): Long? = single(balanceCandidates(raw))

    fun parseBonus(raw: String): Long? = single(bonusCandidates(raw))

    fun isNumericLike(raw: String, allowBonusPrefix: Boolean = false): Boolean =
        if (allowBonusPrefix) bonusCandidates(raw).isNotEmpty() else lootCandidates(raw).isNotEmpty()

    private fun single(candidates: List<Candidate>): Long? =
        candidates.singleOrNull()?.value

    private data class Segment(
        val alternatives: List<String>,
        val length: Int,
        val actualDigits: Int
    )

    private data class Token(
        val segment: Segment?,
        val hasUnknown: Boolean,
        val prefixOnly: Boolean
    )

    private fun candidates(raw: String, minDigits: Int, maxDigits: Int): List<Candidate> {
        val text = raw.replace('\u00A0', ' ').trim()
        if (text.isEmpty()) return emptyList()

        val runs = mutableListOf<List<String>>()
        var run = listOf("")
        var runLength = 0
        var pendingPrefix: List<String>? = null
        var pendingLength = 0

        fun flushRun() {
            if (runLength in minDigits..maxDigits && run.any { it.isNotEmpty() }) {
                runs += run
            }
            run = listOf("")
            runLength = 0
        }

        fun clearPending() {
            pendingPrefix = null
            pendingLength = 0
        }

        fun append(current: List<String>, alternatives: List<String>): List<String> {
            val next = LinkedHashSet<String>(current.size * alternatives.size)
            for (prefix in current) {
                for (suffix in alternatives) {
                    next.add(prefix + suffix)
                    if (next.size >= MAX_CANDIDATES) return next.toList()
                }
            }
            return next.toList()
        }

        for (tokenText in text.split(WHITESPACE)) {
            if (tokenText.isEmpty()) continue
            val token = tokenize(tokenText)
            if (token == null) {
                flushRun()
                clearPending()
                continue
            }
            if (token.hasUnknown) {
                flushRun()
                clearPending()
                val segment = token.segment ?: continue
                if (segment.actualDigits > 0 &&
                    segment.length in minDigits..maxDigits
                ) {
                    runs += segment.alternatives
                }
                continue
            }
            if (token.prefixOnly) continue
            val segment = token.segment ?: continue
            if (segment.actualDigits == 0) {
                if (runLength > 0) {
                    run = append(run, segment.alternatives)
                    runLength += segment.length
                } else {
                    pendingPrefix = append(pendingPrefix ?: listOf(""), segment.alternatives)
                    pendingLength += segment.length
                }
                continue
            }
            if (runLength == 0) {
                run = pendingPrefix ?: listOf("")
                runLength = pendingLength
                pendingPrefix = null
                pendingLength = 0
            }
            run = append(run, segment.alternatives)
            runLength += segment.length
        }
        flushRun()

        val values = LinkedHashMap<Long, String>()
        for (digits in runs.flatten()) {
            val value = digits.toLongOrNull() ?: continue
            values.putIfAbsent(value, digits)
        }
        return values.map { (value, digits) -> Candidate(value, digits) }
    }

    /**
     * A token is clean when every character is a digit, separator, prefix or
     * confusable letter. Tokens containing other letters are split into clean
     * segments; only the segment with the most real digits is considered, so
     * labels such as "Sour Elixir" cannot contribute digits to the number.
     */
    private fun tokenize(text: String): Token? {
        val cleanAlternatives = parseClean(text)
        if (cleanAlternatives != null) {
            val segment = cleanAlternatives
            return Token(
                segment = segment,
                hasUnknown = false,
                prefixOnly = segment.alternatives.isEmpty()
            )
        }

        val segments = mutableListOf<Segment>()
        var start = 0
        for (index in 0..text.length) {
            val boundary = index == text.length || !isKnown(text[index])
            if (!boundary) continue
            if (index > start) {
                parseClean(text.substring(start, index))?.let { segment ->
                    if (segment.actualDigits > 0) segments += segment
                }
            }
            start = index + 1
        }
        if (segments.isEmpty()) return null
        val best = segments.maxWithOrNull(
            compareBy<Segment> { it.actualDigits }.thenBy { it.length }
        )
        return Token(segment = best, hasUnknown = true, prefixOnly = false)
    }

    private fun parseClean(text: String): Segment? {
        if (text.isEmpty()) return null
        var current = listOf("")
        var actualDigits = 0
        var length = 0
        for (char in text) {
            val alternatives: CharArray? = when {
                char.isDigit() -> {
                    actualDigits++
                    charArrayOf(char)
                }

                SEPARATORS.contains(char) -> continue
                PREFIXES.contains(char) -> continue
                else -> DIGIT_ALTERNATIVES[char]
            }
            if (alternatives == null) return null
            val next = LinkedHashSet<String>(current.size * alternatives.size)
            for (prefix in current) {
                for (digit in alternatives) {
                    next.add(prefix + digit)
                    if (next.size >= MAX_CANDIDATES) break
                }
                if (next.size >= MAX_CANDIDATES) break
            }
            current = next.toList()
            length++
        }
        return Segment(
            alternatives = if (length == 0) emptyList() else current,
            length = length,
            actualDigits = actualDigits
        )
    }

    private fun isKnown(char: Char): Boolean =
        char.isDigit() || SEPARATORS.contains(char) || PREFIXES.contains(char) ||
            DIGIT_ALTERNATIVES.containsKey(char)

    private val WHITESPACE = Regex("\\s+")
}
