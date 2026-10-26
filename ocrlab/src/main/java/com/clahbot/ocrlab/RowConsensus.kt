package com.clahbot.ocrlab

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
        val support = LinkedHashMap<Long, LinkedHashSet<String>>()
        for (read in reads) {
            if (!read.hasValue) continue
            for (value in read.candidates.distinct()) {
                support.getOrPut(value) { LinkedHashSet() }.add(read.reader)
            }
        }

        val votingReaders = support.values.flatten().toSet().size
        val eligible = support.filter { (_, readers) ->
            readers.size >= 2 && readers.size * 2 > votingReaders
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
}
