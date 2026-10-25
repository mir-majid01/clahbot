package com.clahbot.ocrlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsGateTest {

    private fun read(gold: Long = 1_000L): ResultsRead = ResultsRead(
        outcome = ResultsOutcome.VICTORY,
        stars = null,
        gold = gold,
        elixir = 2_000L,
        darkElixir = 3_000L,
        bonusGold = 100L,
        bonusElixir = 100L,
        bonusDarkElixir = 10L,
        balanceGold = null,
        balanceElixir = null,
        balanceDarkElixir = null,
        confidence = 0.9,
        needsReview = false,
        issues = emptyList()
    )

    @Test
    fun countsOnlyAfterTwoAgreeingCapturesAndThenIgnoresDuplicates() {
        val gate = ResultsGate(requiredAgreement = 2)

        assertTrue(gate.observe(read()) is GateResult.Stabilizing)
        assertTrue(gate.observe(read()) is GateResult.Counted)
        assertTrue(gate.observe(read()) is GateResult.Duplicate)
        assertTrue(gate.observe(read()) is GateResult.Duplicate)

        assertEquals(GateResult.NonResults, gate.observe(null))
        assertTrue(gate.observe(read(2_000L)) is GateResult.Stabilizing)
        assertTrue(gate.observe(read(2_000L)) is GateResult.Counted)
    }

    @Test
    fun changingCountingValuesRestartsAgreement() {
        val gate = ResultsGate(requiredAgreement = 2)

        assertTrue(gate.observe(read(1_000L)) is GateResult.Stabilizing)
        assertTrue(gate.observe(read(1_500L)) is GateResult.Stabilizing)
        assertTrue(gate.observe(read(1_900L)) is GateResult.Stabilizing)
        assertTrue(gate.observe(read(1_900L)) is GateResult.Counted)
        assertTrue(gate.observe(read(2_500L)) is GateResult.Duplicate)
    }
}
