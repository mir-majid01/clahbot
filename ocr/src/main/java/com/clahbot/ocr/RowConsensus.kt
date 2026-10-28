package com.clahbot.ocr

/**
 * Reader consensus for one row.
 *
 * A value is accepted when at least two independent readers support it and
 * that support is a strict majority of every reader with any numeric evidence
 * for the row. A lone reader is never trusted; it may only be accepted when
 * exact balance arithmetic independently predicts the same value.
 */
object RowConsensus {

    fun resolve(
        reads: List<EngineRowRead>,
        expectedFromMath: Long? = null
    ): ConsensusResult {
        // A reader whose text has a name/label glued to the number may only
        // see the visible tail. Prefer the clean readers when at least two of
        // them produced a number.
        val votes = reads.filter { it.eligibleForVote }
        val cleanVotes = votes.filter { !NumberParser.hasLabelOverlap(it.text) }
        val considered = if (cleanVotes.size >= 2) cleanVotes else votes

        val support = LinkedHashMap<Long, LinkedHashSet<String>>()
        for (read in considered) {
            if (!read.eligibleForVote) continue
            for (value in read.candidates.distinct()) {
                support.getOrPut(value) { LinkedHashSet() }.add(read.reader)
            }
        }

        val votingReaders = support.values.flatten().toSet().size
        val eligible = support.filter { (value, readers) ->
            readers.size >= 2 && readers.size * 2 > votingReaders &&
                !isShortFragment(value, readers.size, support.keys)
        }
        if (eligible.size == 1) {
            val (value, readers) = eligible.entries.single()
            return ConsensusResult(
                value = value,
                sources = readers.toList(),
                status = "consensus"
            )
        }
        if (eligible.size > 1) {
            val sources = eligible.entries
                .sortedByDescending { it.value.size }
                .map { entry -> "${entry.key}:${entry.value.joinToString("/")}" }
            return ConsensusResult(null, sources, "conflict")
        }

        // One value read exactly by at least two readers, with every other
        // reading explainable as a one-digit slip, a lost leading digit, or a
        // short unrelated number (for example a troop level). This covers rows
        // where a name overlay or grouping space corrupts one reader per
        // reader, and it cannot invent a value no reader produced.
        val strong = support.filter {
            it.value.size >= 2 && !isShortFragment(it.key, it.value.size, support.keys)
        }
        if (strong.size == 1) {
            val (value, readers) = strong.entries.single()
            val valueText = value.toString()
            val compatible = support.keys.all { other ->
                if (other == value) return@all true
                val text = other.toString()
                valueText.contains(text) ||
                    withinOneEdit(text, valueText) ||
                    (text.length * 2 < valueText.length &&
                        text.firstOrNull() != valueText.firstOrNull())
            }
            if (compatible) {
                return ConsensusResult(
                    value = value,
                    sources = readers.toList() + Readers.NEAR_MATCH,
                    status = "near_consensus"
                )
            }
        }

        if (expectedFromMath != null) {
            val readers = support[expectedFromMath].orEmpty()
            if (readers.isNotEmpty()) {
                return ConsensusResult(
                    value = expectedFromMath,
                    sources = readers.toList() + Readers.BALANCE_MATH,
                    status = "balance_math"
                )
            }
        }

        return when {
            support.isEmpty() -> ConsensusResult(null, emptyList(), "not_captured")
            support.size == 1 -> {
                val readers = support.entries.single().value
                ConsensusResult(null, readers.toList(), "single_reader")
            }

            else -> {
                val sources = support.entries.map { entry ->
                    "${entry.key}:${entry.value.joinToString("/")}"
                }
                ConsensusResult(null, sources, "conflict")
            }
        }
    }

    /**
     * A short value that is only the tail of a longer reading is likely a
     * fragment: a name overlay hides the leading digits, and two readers can
     * agree on the visible remainder. Three matching readers, or a value that
     * is nearly as long as the longer read, are accepted as normal.
     */
    private fun isShortFragment(value: Long, supporters: Int, values: Set<Long>): Boolean {
        if (supporters >= 3) return false
        val text = value.toString()
        return values.any { other ->
            val longer = other.toString()
            longer.length > text.length && longer.endsWith(text) &&
                text.length * 3 <= longer.length * 2
        }
    }

    private fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }
}
