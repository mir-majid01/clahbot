package com.clahbot.walls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallMathTest {

    private fun line(done: Int = 34, total: Int = 120) =
        WallLine(ax = 0.2f, ay = 0.8f, bx = 0.8f, by = 0.4f, totalWalls = total, wallsDone = done)

    // ---------- affordable walls ----------

    @Test
    fun `affordable count divides budget by cost`() {
        assertEquals(
            4,
            WallMath.wallsAffordable(
                balance = 10_000_000,
                reserve = 2_000_000,
                cost = 2_000_000,
                maxPerCycle = 40,
                wallsRemaining = 86
            )
        )
    }

    @Test
    fun `exact budget buys exactly one wall`() {
        assertEquals(1, WallMath.wallsAffordable(4_000_000, 2_000_000, 2_000_000, 40, 86))
    }

    @Test
    fun `below cost buys nothing`() {
        assertEquals(0, WallMath.wallsAffordable(3_999_999, 2_000_000, 2_000_000, 40, 86))
    }

    @Test
    fun `reserve is never spent`() {
        assertEquals(0, WallMath.wallsAffordable(2_000_000, 2_000_000, 1, 40, 86))
        assertEquals(0, WallMath.wallsAffordable(1_000_000, 2_000_000, 1_000_000, 40, 86))
    }

    @Test
    fun `cycle maximum caps the count`() {
        assertEquals(
            40,
            WallMath.wallsAffordable(1_000_000_000, 0, 1_000_000, maxPerCycle = 40, wallsRemaining = 500)
        )
    }

    @Test
    fun `remaining walls cap the count`() {
        assertEquals(
            1,
            WallMath.wallsAffordable(1_000_000_000, 0, 1_000_000, maxPerCycle = 40, wallsRemaining = 1)
        )
    }

    @Test
    fun `invalid cost or zero limit buys nothing`() {
        assertEquals(0, WallMath.wallsAffordable(10_000_000, 0, 0, 40, 86))
        assertEquals(0, WallMath.wallsAffordable(10_000_000, 0, -5, 40, 86))
        assertEquals(0, WallMath.wallsAffordable(10_000_000, 0, 1_000_000, 0, 86))
        assertEquals(0, WallMath.wallsAffordable(10_000_000, 0, 1_000_000, 40, 0))
    }

    // ---------- price agreement ----------

    @Test
    fun `price within three percent is ok`() {
        assertEquals(WallMath.PriceVerdict.OK, WallMath.priceCheck(1_000_000, 1_000_000))
        assertEquals(WallMath.PriceVerdict.OK, WallMath.priceCheck(1_000_000, 1_030_000))
        assertEquals(WallMath.PriceVerdict.OK, WallMath.priceCheck(1_000_000, 970_000))
    }

    @Test
    fun `price more than three percent higher is refused`() {
        assertEquals(WallMath.PriceVerdict.HIGHER, WallMath.priceCheck(1_000_000, 1_030_001))
        assertEquals(WallMath.PriceVerdict.HIGHER, WallMath.priceCheck(1_000_000, 2_000_000))
    }

    @Test
    fun `lower price is reported but allowed`() {
        assertEquals(WallMath.PriceVerdict.LOWER, WallMath.priceCheck(1_000_000, 900_000))
    }

    @Test
    fun `missing price on either side is refused`() {
        assertEquals(WallMath.PriceVerdict.HIGHER, WallMath.priceCheck(0, 1_000_000))
        assertEquals(WallMath.PriceVerdict.HIGHER, WallMath.priceCheck(1_000_000, 0))
    }

    // ---------- balance drop verification ----------

    @Test
    fun `exact drop is accepted`() {
        assertTrue(WallMath.balanceDropOk(10_000_000, 8_000_000, 2_000_000))
    }

    @Test
    fun `collector income shrink within ten percent is accepted`() {
        assertTrue(WallMath.balanceDropOk(10_000_000, 8_200_000, 2_000_000))
    }

    @Test
    fun `balance rising after upgrade is refused`() {
        assertFalse(WallMath.balanceDropOk(10_000_000, 10_500_000, 2_000_000))
    }

    @Test
    fun `drop far smaller than the price is refused`() {
        assertFalse(WallMath.balanceDropOk(10_000_000, 9_000_000, 2_000_000))
    }

    @Test
    fun `drop far larger than the price is refused`() {
        assertFalse(WallMath.balanceDropOk(10_000_000, 6_000_000, 2_000_000))
    }

    @Test
    fun `zero expectation cannot be verified and passes`() {
        assertTrue(WallMath.balanceDropOk(10_000_000, 1, 0))
    }

    // ---------- spend caps ----------

    @Test
    fun `spend caps block before they are exceeded`() {
        assertTrue(WallMath.spendCapsOk(0, 0, 2_000_000, capSession = 10_000_000, capDay = 0))
        assertFalse(WallMath.spendCapsOk(9_000_000, 0, 2_000_000, capSession = 10_000_000, capDay = 0))
        assertTrue(WallMath.spendCapsOk(8_000_000, 0, 2_000_000, capSession = 10_000_000, capDay = 0))
    }

    @Test
    fun `daily cap applies across sessions`() {
        assertFalse(WallMath.spendCapsOk(0, 9_000_000, 2_000_000, capSession = 0, capDay = 10_000_000))
        assertTrue(WallMath.spendCapsOk(0, 7_999_999, 2_000_000, capSession = 0, capDay = 10_000_000))
    }

    @Test
    fun `zero caps mean no cap`() {
        assertTrue(WallMath.spendCapsOk(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, 1, 0, 0))
    }

    // ---------- line geometry ----------

    @Test
    fun `tap point zero is marker A and the last wall is marker B`() {
        val line = line(done = 0, total = 10)
        assertEquals(0.2f to 0.8f, WallMath.tapPoint(line, 0))
        assertEquals(0.8f to 0.4f, WallMath.tapPoint(line, 9))
    }

    @Test
    fun `tap points are evenly spread along the line`() {
        val line = line(done = 0, total = 3)
        val middle = WallMath.tapPoint(line, 1)
        assertEquals(0.5f, middle.first, 0.0001f)
        assertEquals(0.6f, middle.second, 0.0001f)
    }

    @Test
    fun `tap index is clamped inside the line`() {
        val line = line(done = 0, total = 5)
        assertEquals(0.2f to 0.8f, WallMath.tapPoint(line, -3))
        assertEquals(0.8f to 0.4f, WallMath.tapPoint(line, 99))
    }

    @Test
    fun `a one wall line degenerates to marker A`() {
        val line = line(done = 0, total = 1)
        assertEquals(0.2f to 0.8f, WallMath.tapPoint(line, 0))
    }

    @Test
    fun `neighbours span three walls on both sides`() {
        assertEquals(listOf(4, 6, 3, 7, 2, 8), WallMath.neighbourIndexes(5, 120))
    }

    @Test
    fun `neighbours stay inside the line`() {
        assertEquals(listOf(1, 2, 3), WallMath.neighbourIndexes(0, 120))
        assertEquals(listOf(118, 117, 116), WallMath.neighbourIndexes(119, 120))
    }

    // ---------- progress ----------

    @Test
    fun `remaining walls never go negative`() {
        assertEquals(86, WallMath.wallsRemaining(line(done = 34, total = 120)))
        assertEquals(0, WallMath.wallsRemaining(line(done = 120, total = 120)))
        assertEquals(0, WallMath.wallsRemaining(line(done = 130, total = 120)))
    }

    @Test
    fun `advancing clamps at the total`() {
        assertEquals(44, WallMath.advanced(line(done = 34, total = 120), 10).wallsDone)
        assertEquals(120, WallMath.advanced(line(done = 34, total = 120), 500).wallsDone)
        assertEquals(34, WallMath.advanced(line(done = 34, total = 120), -5).wallsDone)
    }
}
