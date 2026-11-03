package com.clahbot.walls

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Pure cost and geometry maths for the wall upgrader. */
object WallMath {

    enum class PriceVerdict { OK, HIGHER, LOWER }

    /**
     * Walls this resource can afford right now: floor((balance - reserve) /
     * cost), clamped by the per-cycle limit and the walls left in the line.
     */
    fun wallsAffordable(
        balance: Long,
        reserve: Long,
        cost: Long,
        maxPerCycle: Int,
        wallsRemaining: Int
    ): Int {
        if (cost <= 0L || maxPerCycle <= 0 || wallsRemaining <= 0) return 0
        val budget = balance - reserve
        if (budget < cost) return 0
        val affordable = floor(budget.toDouble() / cost.toDouble()).toInt()
        return affordable.coerceIn(0, min(maxPerCycle, wallsRemaining))
    }

    /**
     * The upgrade price must not be more than 3% above the user's setting. A
     * lower price is fine (discount or wall level changed) but is reported so
     * the user can update the cost field.
     */
    fun priceCheck(expected: Long, actual: Long): PriceVerdict {
        if (expected <= 0L || actual <= 0L) return PriceVerdict.HIGHER
        return when {
            actual > expected + expected * PRICE_TOLERANCE -> PriceVerdict.HIGHER
            actual < expected - expected * PRICE_TOLERANCE -> PriceVerdict.LOWER
            else -> PriceVerdict.OK
        }
    }

    /**
     * After the confirm dialog closes, the balance must have dropped by about
     * the expected cost. Collector income on the home village can shrink the
     * drop, so the lower bound is loose; the balance may never rise.
     */
    fun balanceDropOk(before: Long, after: Long, expected: Long): Boolean {
        if (expected <= 0L || before <= 0L) return true
        if (after > before) return false
        val drop = before - after
        val lower = (expected * DROP_LOWER_FACTOR).toLong()
        val upper = (expected * DROP_UPPER_FACTOR).toLong()
        return drop in lower..upper
    }

    /**
     * Progress index -> tap position along the A->B line, in frame fractions.
     * Index 0 taps marker A; index totalWalls-1 taps marker B.
     */
    fun tapPoint(line: WallLine, index: Int): Pair<Float, Float> {
        val total = max(2, line.totalWalls)
        val clamped = index.coerceIn(0, total - 1)
        val t = clamped.toFloat() / (total - 1)
        return line.ax + (line.bx - line.ax) * t to
            line.ay + (line.by - line.ay) * t
    }

    /** Neighbour positions to try when the wall selection did not verify. */
    fun neighbourIndexes(index: Int, totalWalls: Int, span: Int = NEIGHBOUR_SPAN): List<Int> {
        val result = mutableListOf<Int>()
        for (distance in 1..span) {
            result += index - distance
            result += index + distance
        }
        return result.filter { it in 0 until max(1, totalWalls) }.distinct()
    }

    fun wallsRemaining(line: WallLine): Int =
        max(0, line.totalWalls - line.wallsDone)

    fun advanced(line: WallLine, walls: Int): WallLine =
        line.copy(wallsDone = min(line.totalWalls, line.wallsDone + max(0, walls)))

    /**
     * True when spending [expected] keeps both the session total and the
     * calendar-day total at or below their caps; a cap of 0 means no cap.
     */
    fun spendCapsOk(
        sessionSpent: Long,
        daySpent: Long,
        expected: Long,
        capSession: Long,
        capDay: Long
    ): Boolean {
        if (capSession > 0 && sessionSpent + expected > capSession) return false
        if (capDay > 0 && daySpent + expected > capDay) return false
        return true
    }

    const val PRICE_TOLERANCE = 0.03
    const val DROP_LOWER_FACTOR = 0.90
    const val DROP_UPPER_FACTOR = 1.02
    const val NEIGHBOUR_SPAN = 3
}
