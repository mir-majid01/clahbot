package com.clahbot.results

import com.clahbot.ocr.ResultsOutcome
import com.clahbot.ocr.ResultsRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableReadGateTest {

    private fun read(
        gold: Long?,
        elixir: Long?,
        dark: Long?,
        outcome: ResultsOutcome = ResultsOutcome.VICTORY
    ) = ResultsRead(
        outcome = outcome,
        stars = null,
        gold = gold,
        elixir = elixir,
        darkElixir = dark,
        bonusGold = null,
        bonusElixir = null,
        bonusDarkElixir = null,
        balanceGold = null,
        balanceElixir = null,
        balanceDarkElixir = null,
        confidence = 1.0,
        needsReview = false,
        issues = emptyList()
    )

    @Test
    fun theThreeMidAnimationReadsFromThe12048ScreenNeverStabilize() {
        val gate = StableReadGate()
        assertNull(gate.offer(read(25_412_267, null, 35_016)))
        assertNull(gate.offer(read(2_542, null, 35_016)))
        assertNull(gate.offer(read(25_442, null, 35_016)))
        assertNull(gate.stableRead())
    }

    @Test
    fun twoIdenticalCompleteReadsStabilize() {
        val gate = StableReadGate()
        assertNull(gate.offer(read(2_541_267, 1_668_782, 35_016)))
        assertEquals(2_541_267L, gate.offer(read(2_541_267, 1_668_782, 35_016))?.gold)
        assertEquals(35_016L, gate.stableRead()?.darkElixir)
    }

    @Test
    fun aPartialFrameBetweenTwoIdenticalReadsDoesNotResetStability() {
        val gate = StableReadGate()
        assertNull(gate.offer(read(2_541_267, 1_668_782, 35_016)))
        assertNull(gate.offer(read(2_541_267, null, 35_016)))
        assertEquals(2_541_267L, gate.offer(read(2_541_267, 1_668_782, 35_016))?.gold)
    }

    @Test
    fun aDifferentCompleteReadResetsTheCandidate() {
        val gate = StableReadGate()
        assertNull(gate.offer(read(1_000, 2_000, 3_000)))
        assertNull(gate.offer(read(1_100, 2_000, 3_000)))
        assertNull(gate.offer(read(1_000, 2_000, 3_000)))
        assertEquals(1_000L, gate.offer(read(1_000, 2_000, 3_000))?.gold)
    }
}
