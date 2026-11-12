package com.clahbot.walls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Combined AND trigger (Auto Walls 2.0 PRD §2.1/§4). The expression is
 * "AttackCountReached AND ResourceConditionsSatisfied"; every other required
 * condition is ANDed too, and each failure carries the exact reason for waiting.
 */
class AutoWallsTriggerTest {

    private fun evaluate(
        config: WallTriggerConfig = WallTriggerConfig(),
        selection: WallSelectionConfig = WallSelectionConfig(),
        counters: WallTriggerCounters = WallTriggerCounters(attacksSinceVerified = 10),
        enabled: Boolean = true,
        gold: Long? = 5_000_000,
        elixir: Long? = 5_000_000,
        now: Long = 1_000_000,
        session: Int = 0,
        actualCost: Long? = null,
        costCurrency: String? = null
    ): TriggerEvaluation = AutoWallsTrigger.evaluate(
        config, selection, counters,
        TriggerEvidence(
            enabled = enabled,
            resources = WallCounters.Reading(gold = gold, elixir = elixir),
            now = now,
            upgradesThisSession = session,
            actualCost = actualCost,
            costCurrency = costCurrency
        )
    )

    private fun TriggerEvaluation.condition(name: String): TriggerCondition =
        conditions.first { it.name == name }

    @Test
    fun `all conditions passing allows discovery to begin`() {
        val result = evaluate()
        assertTrue(result.pass)
        assertNull(result.waitingReason)
        assertTrue(result.conditions.all { it.passed })
    }

    @Test
    fun `every required condition is reported by name`() {
        assertEquals(
            listOf(
                "enabled", "selection", "attacks", "threshold",
                "reserve", "cooldown", "session limit", "run limit"
            ),
            evaluate().conditions.map { it.name }
        )
    }

    @Test
    fun `an attack counter below the configured count waits`() {
        val result = evaluate(counters = WallTriggerCounters(attacksSinceVerified = 9))
        assertFalse(result.pass)
        assertFalse(result.condition("attacks").passed)
        assertTrue(result.condition("attacks").detail.contains("9"))
        assertTrue(result.condition("attacks").detail.contains("10"))
        assertTrue(result.condition("threshold").passed)
    }

    @Test
    fun `the PRD example waits when gold is below threshold despite attacks reached`() {
        val result = evaluate(
            selection = WallSelectionConfig(currency = WallCurrency.GOLD),
            gold = 4_800_000
        )
        assertFalse(result.pass)
        assertTrue(result.condition("attacks").passed)
        assertFalse(result.condition("threshold").passed)
        assertTrue(result.condition("threshold").detail.contains("below threshold"))
    }

    @Test
    fun `one passing condition is never enough`() {
        assertFalse(evaluate(counters = WallTriggerCounters(attacksSinceVerified = 9)).pass)
        assertFalse(
            evaluate(
                selection = WallSelectionConfig(currency = WallCurrency.GOLD),
                gold = 4_800_000
            ).pass
        )
        assertFalse(
            evaluate(
                selection = WallSelectionConfig(currency = WallCurrency.GOLD),
                counters = WallTriggerCounters(attacksSinceVerified = 9),
                gold = 4_800_000
            ).pass
        )
    }

    @Test
    fun `the reserve is checked against the actual upgrade cost`() {
        val result = evaluate(actualCost = 4_500_000, costCurrency = "gold")
        assertFalse(result.pass)
        assertFalse(result.condition("reserve").passed)
        assertTrue(result.condition("reserve").detail.contains("reserve"))
    }

    @Test
    fun `a cost that preserves the reserve passes`() {
        assertTrue(evaluate(actualCost = 4_000_000, costCurrency = "gold").pass)
    }

    @Test
    fun `a balance already below the reserve waits even without a known cost`() {
        val result = evaluate(
            config = WallTriggerConfig(goldThreshold = 0),
            selection = WallSelectionConfig(currency = WallCurrency.GOLD),
            gold = 900_000
        )
        assertFalse(result.pass)
        assertTrue(result.condition("threshold").passed)
        assertFalse(result.condition("reserve").passed)
    }

    @Test
    fun `unreadable balances never pass the resource conditions`() {
        val result = evaluate(gold = null, elixir = null)
        assertFalse(result.pass)
        assertFalse(result.condition("threshold").passed)
        assertTrue(result.condition("threshold").detail.contains("not readable"))
    }

    @Test
    fun `elixir mode reads the elixir threshold and reserve`() {
        val result = evaluate(
            selection = WallSelectionConfig(currency = WallCurrency.ELIXIR),
            elixir = 4_000_000
        )
        assertFalse(result.pass)
        assertFalse(result.condition("threshold").passed)
        assertTrue(result.condition("threshold").detail.contains("elixir"))
    }

    @Test
    fun `both mode accepts either currency meeting threshold and reserve`() {
        val result = evaluate(
            selection = WallSelectionConfig(currency = WallCurrency.BOTH),
            gold = 1_000_000,
            elixir = 6_000_000
        )
        assertTrue(result.pass)
    }

    @Test
    fun `both mode waits when neither currency qualifies`() {
        val result = evaluate(
            selection = WallSelectionConfig(currency = WallCurrency.BOTH),
            gold = 1_000_000,
            elixir = 2_000_000
        )
        assertFalse(result.pass)
    }

    @Test
    fun `both mode with a known cost checks that currency's reserve`() {
        val result = evaluate(
            selection = WallSelectionConfig(currency = WallCurrency.BOTH),
            actualCost = 4_500_000,
            costCurrency = "gold"
        )
        assertFalse(result.pass)
        assertFalse(result.condition("reserve").passed)
    }

    @Test
    fun `an active cooldown waits with the remaining time`() {
        val result = evaluate(
            config = WallTriggerConfig(cooldownMillis = 60_000),
            counters = WallTriggerCounters(attacksSinceVerified = 10, lastVerifiedAt = 970_000)
        )
        assertFalse(result.pass)
        assertFalse(result.condition("cooldown").passed)
        assertTrue(result.condition("cooldown").detail.contains("30"))
    }

    @Test
    fun `an elapsed cooldown passes`() {
        assertTrue(
            evaluate(
                config = WallTriggerConfig(cooldownMillis = 60_000),
                counters = WallTriggerCounters(attacksSinceVerified = 10, lastVerifiedAt = 940_000)
            ).pass
        )
    }

    @Test
    fun `no previous verified upgrade ignores the cooldown`() {
        assertTrue(evaluate(config = WallTriggerConfig(cooldownMillis = 60_000)).pass)
    }

    @Test
    fun `the session upgrade limit waits when reached`() {
        val result = evaluate(config = WallTriggerConfig(maxUpgradesPerSession = 3), session = 3)
        assertFalse(result.pass)
        assertFalse(result.condition("session limit").passed)
        assertTrue(result.condition("run limit").passed)
    }

    @Test
    fun `the run upgrade limit waits independently of the session limit`() {
        val result = evaluate(
            config = WallTriggerConfig(maxUpgradesPerRun = 2),
            counters = WallTriggerCounters(attacksSinceVerified = 10, upgradesThisRun = 2)
        )
        assertFalse(result.pass)
        assertFalse(result.condition("run limit").passed)
        assertTrue(result.condition("session limit").passed)
    }

    @Test
    fun `absent limits mean unlimited`() {
        assertTrue(
            evaluate(
                session = 100,
                counters = WallTriggerCounters(attacksSinceVerified = 10, upgradesThisRun = 100)
            ).pass
        )
    }

    @Test
    fun `disabled automation waits`() {
        val result = evaluate(enabled = false)
        assertFalse(result.pass)
        assertFalse(result.condition("enabled").passed)
    }

    @Test
    fun `a custom priority without criteria waits instead of guessing`() {
        val result = evaluate(selection = WallSelectionConfig(strategy = WallSelectionStrategy.CUSTOM))
        assertFalse(result.pass)
        assertFalse(result.condition("selection").passed)
        assertTrue(
            evaluate(
                selection = WallSelectionConfig(
                    strategy = WallSelectionStrategy.CUSTOM,
                    customOrder = listOf(WallCriterion.CHEAPEST)
                )
            ).pass
        )
    }

    @Test
    fun `threshold and reserve stay separate values`() {
        val result = evaluate(
            selection = WallSelectionConfig(currency = WallCurrency.GOLD),
            gold = 4_900_000
        )
        assertFalse(result.condition("threshold").passed)
        assertTrue(result.condition("reserve").passed)
    }

    @Test
    fun `the waiting reason is the first failed condition`() {
        val result = evaluate(
            counters = WallTriggerCounters(attacksSinceVerified = 9),
            gold = 4_800_000
        )
        assertEquals(result.condition("attacks").detail, result.waitingReason)
    }

    @Test
    fun `counters track attacks, verified upgrades and run starts`() {
        val start = WallTriggerCounters()
        val attacked = start.onAttackCompleted().onAttackCompleted()
        assertEquals(2, attacked.attacksSinceVerified)
        val verified = attacked.onVerifiedUpgrade(500L)
        assertEquals(0, verified.attacksSinceVerified)
        assertEquals(1, verified.upgradesThisRun)
        assertEquals(500L, verified.lastVerifiedAt)
        val restarted = verified.onRunStarted()
        assertEquals(0, restarted.upgradesThisRun)
        assertEquals(0, restarted.attacksSinceVerified)
        assertEquals(500L, restarted.lastVerifiedAt)
    }

    // ---- boundary conditions ----------------------------------------------

    @Test
    fun `attacks exactly at the configured count passes`() {
        assertTrue(
            evaluate(counters = WallTriggerCounters(attacksSinceVerified = 10))
                .condition("attacks").passed
        )
    }

    @Test
    fun `gold exactly at the threshold passes but one below waits`() {
        val gold = WallSelectionConfig(currency = WallCurrency.GOLD)
        assertTrue(evaluate(selection = gold, gold = 5_000_000).condition("threshold").passed)
        assertFalse(evaluate(selection = gold, gold = 4_999_999).condition("threshold").passed)
    }

    @Test
    fun `a cost leaving exactly the reserve passes but one below waits`() {
        assertTrue(evaluate(actualCost = 4_000_000, costCurrency = "gold").condition("reserve").passed)
        assertFalse(evaluate(actualCost = 4_000_001, costCurrency = "gold").condition("reserve").passed)
    }

    @Test
    fun `a cooldown exactly elapsed passes but one millisecond short waits`() {
        val config = WallTriggerConfig(cooldownMillis = 60_000)
        assertTrue(
            evaluate(
                config = config,
                counters = WallTriggerCounters(attacksSinceVerified = 10, lastVerifiedAt = 940_000)
            ).condition("cooldown").passed
        )
        assertFalse(
            evaluate(
                config = config,
                counters = WallTriggerCounters(attacksSinceVerified = 10, lastVerifiedAt = 940_001)
            ).condition("cooldown").passed
        )
    }

    @Test
    fun `the session limit allows one below but not at the limit`() {
        val config = WallTriggerConfig(maxUpgradesPerSession = 3)
        assertTrue(evaluate(config = config, session = 2).condition("session limit").passed)
        assertFalse(evaluate(config = config, session = 3).condition("session limit").passed)
    }

    @Test
    fun `the run limit allows one below but not at the limit`() {
        val config = WallTriggerConfig(maxUpgradesPerRun = 2)
        assertTrue(
            evaluate(config = config, counters = WallTriggerCounters(attacksSinceVerified = 10, upgradesThisRun = 1))
                .condition("run limit").passed
        )
        assertFalse(
            evaluate(config = config, counters = WallTriggerCounters(attacksSinceVerified = 10, upgradesThisRun = 2))
                .condition("run limit").passed
        )
    }

    // ---- payment-mode combinations ----------------------------------------

    @Test
    fun `gold mode ignores a large elixir balance`() {
        assertFalse(
            evaluate(
                selection = WallSelectionConfig(currency = WallCurrency.GOLD),
                gold = 1_000_000,
                elixir = 9_000_000
            ).pass
        )
    }

    @Test
    fun `elixir mode ignores a large gold balance`() {
        assertFalse(
            evaluate(
                selection = WallSelectionConfig(currency = WallCurrency.ELIXIR),
                gold = 9_000_000,
                elixir = 1_000_000
            ).pass
        )
    }

    @Test
    fun `gold mode passes on gold alone and elixir mode on elixir alone`() {
        assertTrue(
            evaluate(
                selection = WallSelectionConfig(currency = WallCurrency.GOLD),
                gold = 5_000_000,
                elixir = 0
            ).pass
        )
        assertTrue(
            evaluate(
                selection = WallSelectionConfig(currency = WallCurrency.ELIXIR),
                gold = 0,
                elixir = 5_000_000
            ).pass
        )
    }

    @Test
    fun `a known cost pins the currency even in both mode`() {
        // Gold can afford it, but the confirmed cost is elixir and elixir breaks the reserve.
        val result = evaluate(
            selection = WallSelectionConfig(currency = WallCurrency.BOTH),
            gold = 9_000_000,
            elixir = 5_000_000,
            actualCost = 4_500_000,
            costCurrency = "elixir"
        )
        assertFalse(result.pass)
        assertFalse(result.condition("reserve").passed)
        assertTrue(result.condition("reserve").detail.contains("elixir"))
    }

    // ---- missing / partial OCR readings -----------------------------------

    @Test
    fun `gold mode waits when gold is unreadable even if elixir is read`() {
        val result = evaluate(
            selection = WallSelectionConfig(currency = WallCurrency.GOLD),
            gold = null,
            elixir = 9_000_000
        )
        assertFalse(result.pass)
        assertTrue(result.condition("threshold").detail.contains("not readable"))
    }

    @Test
    fun `elixir mode waits when elixir is unreadable even if gold is read`() {
        assertFalse(
            evaluate(
                selection = WallSelectionConfig(currency = WallCurrency.ELIXIR),
                gold = 9_000_000,
                elixir = null
            ).pass
        )
    }

    @Test
    fun `both mode passes when only elixir is readable and qualifies`() {
        assertTrue(
            evaluate(
                selection = WallSelectionConfig(currency = WallCurrency.BOTH),
                gold = null,
                elixir = 6_000_000
            ).pass
        )
    }

    @Test
    fun `both mode waits when the only readable currency is below threshold`() {
        assertFalse(
            evaluate(
                selection = WallSelectionConfig(currency = WallCurrency.BOTH),
                gold = null,
                elixir = 1_000_000
            ).pass
        )
    }

    // ---- counter errors / unknown values ----------------------------------

    @Test
    fun `a negative attack counter is treated as zero and waits`() {
        val result = evaluate(counters = WallTriggerCounters(attacksSinceVerified = -5))
        assertFalse(result.pass)
        assertFalse(result.condition("attacks").passed)
    }

    @Test
    fun `a non-positive attack requirement never auto-satisfies the attack condition`() {
        assertFalse(evaluate(config = WallTriggerConfig(attacksBetweenUpgrades = 0)).condition("attacks").passed)
        assertFalse(evaluate(config = WallTriggerConfig(attacksBetweenUpgrades = -3)).condition("attacks").passed)
    }

    @Test
    fun `a negative run counter is treated as zero, not a bypass`() {
        val result = evaluate(
            config = WallTriggerConfig(maxUpgradesPerRun = 1),
            counters = WallTriggerCounters(attacksSinceVerified = 10, upgradesThisRun = -2)
        )
        assertTrue(result.condition("run limit").passed)
        assertTrue(result.pass)
    }
}