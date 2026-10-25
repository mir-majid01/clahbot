package com.clahbot.ocrlab

sealed interface GateResult {
    data class Counted(val read: ResultsRead) : GateResult
    data class Duplicate(val read: ResultsRead) : GateResult
    data class Stabilizing(val read: ResultsRead) : GateResult
    data object NonResults : GateResult
}

/**
 * Counts a results screen once, only after [requiredAgreement] consecutive
 * captures agree, and re-arms only after a non-results screen is observed.
 */
class ResultsGate(private val requiredAgreement: Int = 2) {

    private var armed = true
    private var candidateFingerprint: String? = null
    private var candidateCount = 0

    fun observe(read: ResultsRead?): GateResult {
        if (read == null) {
            armed = true
            candidateFingerprint = null
            candidateCount = 0
            return GateResult.NonResults
        }
        if (!armed) return GateResult.Duplicate(read)

        if (read.fingerprint == candidateFingerprint) {
            candidateCount++
        } else {
            candidateFingerprint = read.fingerprint
            candidateCount = 1
        }
        if (candidateCount < requiredAgreement) {
            return GateResult.Stabilizing(read)
        }
        armed = false
        candidateFingerprint = null
        candidateCount = 0
        return GateResult.Counted(read)
    }
}
