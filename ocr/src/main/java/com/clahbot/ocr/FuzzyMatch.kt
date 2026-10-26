package com.clahbot.ocr

import java.util.Locale

/**
 * The game's display font loses letters in OCR ("VicoRY"). Accept a small edit
 * distance for the two result words, but never for arbitrary text.
 */
object FuzzyMatch {

    fun normalized(text: String): String =
        text.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    fun matchesResult(text: String, target: String): Boolean {
        val normalized = normalized(text)
        if (normalized.contains(target)) return true
        if (normalized.length !in (target.length - 2)..(target.length + 2)) return false
        return levenshtein(normalized, target) <= 2
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
