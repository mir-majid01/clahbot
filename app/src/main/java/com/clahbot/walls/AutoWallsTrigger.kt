package com.clahbot.walls

/**
 * Trigger settings (Auto Walls 2.0 PRD §5.1/§5.4). The threshold is the balance
 * needed to consider an upgrade; the reserve is what must remain after paying
 * the actual cost. They are separate values and are never conflated.
 */
data class WallTriggerConfig(
    val attacksBetweenUpgrades: Int = 10,
    val goldThreshold: Long = 5_000_000,
    val elixirThreshold: Long = 5_000_000,
    val goldReserve: Long = 1_000_000,
    val elixirReserve: Long = 1_000_000,
    val cooldownMillis: Long = 0L,
    /** null = no limit. */
    val maxUpgradesPerSession: Int? = null,
    val maxUpgradesPerRun: Int? = null
)

/**
 * Counters the trigger needs (PRD §4/§11). Attacks count completed attacks since
 * the previous *verified* upgrade; only a verified upgrade resets them.
 */
data class WallTriggerCounters(
    val attacksSinceVerified: Int = 0,
    val upgradesThisRun: Int = 0,
    val lastVerifiedAt: Long? = null
) {
    fun onAttackCompleted(): WallTriggerCounters = copy(attacksSinceVerified = attacksSinceVerified + 1)

    fun onVerifiedUpgrade(at: Long): WallTriggerCounters = copy(
        attacksSinceVerified = 0,
        upgradesThisRun = upgradesThisRun + 1,
        lastVerifiedAt = at
    )

    fun onRunStarted(): WallTriggerCounters = copy(upgradesThisRun = 0)
}

/** What survives a restart: the trigger settings and their counters. */
data class WallTriggerState(
    val config: WallTriggerConfig = WallTriggerConfig(),
    val counters: WallTriggerCounters = WallTriggerCounters()
)

/** Live readings the evaluator cannot know itself. */
data class TriggerEvidence(
    val enabled: Boolean,
    val resources: WallCounters.Reading,
    val now: Long,
    val upgradesThisSession: Int = 0,
    /** Actual confirmed price, when re-checking the reserve before a tap. */
    val actualCost: Long? = null,
    val costCurrency: String? = null
)

data class TriggerCondition(val name: String, val passed: Boolean, val detail: String)

data class TriggerEvaluation(val conditions: List<TriggerCondition>) {
    val pass: Boolean get() = conditions.all { it.passed }

    /** The exact reason for waiting, for the controller and the history. */
    val waitingReason: String? get() = conditions.firstOrNull { !it.passed }?.detail
}

/**
 * The single combined trigger evaluator (PRD §2.1/§4):
 * "AttackCountReached AND ResourceConditionsSatisfied", ANDed with reserve,
 * cooldown, upgrade limits, the enabled state and the selected wall
 * configuration. There is no OR mode: every condition must pass, and each
 * failure reports why the automation is waiting.
 *
 * The reserve is checked against the actual upgrade cost whenever it is known
 * ([TriggerEvidence.actualCost]); before discovery it only requires the balance
 * to be above the reserve, because no price exists yet.
 */
object AutoWallsTrigger {

    fun evaluate(
        config: WallTriggerConfig,
        selection: WallSelectionConfig,
        counters: WallTriggerCounters,
        evidence: TriggerEvidence
    ): TriggerEvaluation {
        val conditions = mutableListOf<TriggerCondition>()

        conditions += TriggerCondition(
            "enabled", evidence.enabled,
            if (evidence.enabled) "Auto Walls is on" else "Auto Walls is switched off"
        )

        val selectionOk = selection.strategy != WallSelectionStrategy.CUSTOM ||
            selection.customOrder.isNotEmpty()
        conditions += TriggerCondition(
            "selection", selectionOk,
            if (selectionOk) "${selection.strategy} priority configured"
            else "custom priority has no criteria"
        )

        // A corrupt negative counter is unknown, never progress: treat it as 0.
        // A non-positive attack requirement is misconfigured and must wait, not
        // auto-satisfy (unknown values are never treated as satisfied).
        val attacks = counters.attacksSinceVerified.coerceAtLeast(0)
        val required = config.attacksBetweenUpgrades
        val attacksOk = required > 0 && attacks >= required
        conditions += TriggerCondition(
            "attacks", attacksOk,
            when {
                required <= 0 -> "completed-attack count is not configured"
                attacksOk -> "$attacks attacks since the last verified upgrade"
                else -> "attacks since last verified upgrade: $attacks of $required"
            }
        )

        val allowed = when {
            evidence.actualCost != null && evidence.costCurrency != null -> listOf(evidence.costCurrency)
            selection.currency == WallCurrency.GOLD -> listOf("gold")
            selection.currency == WallCurrency.ELIXIR -> listOf("elixir")
            else -> listOf("gold", "elixir")
        }
        val checks = allowed.map { currency ->
            val balance = if (currency == "gold") evidence.resources.gold else evidence.resources.elixir
            val threshold = if (currency == "gold") config.goldThreshold else config.elixirThreshold
            val reserve = if (currency == "gold") config.goldReserve else config.elixirReserve
            val cost = if (currency == evidence.costCurrency) evidence.actualCost ?: 0L else 0L
            CurrencyCheck(
                currency, balance, threshold, reserve, cost,
                thresholdOk = balance != null && balance >= threshold,
                reserveOk = balance != null && balance - cost >= reserve
            )
        }
        // In Both mode one currency must satisfy threshold AND reserve together.
        val chosen = checks.firstOrNull { it.thresholdOk && it.reserveOk } ?: checks.first()
        conditions += TriggerCondition(
            "threshold", chosen.thresholdOk,
            when {
                chosen.balance == null -> "${chosen.currency} balance not readable"
                chosen.thresholdOk -> "${chosen.currency} ${chosen.balance} meets threshold ${chosen.threshold}"
                else -> "${chosen.currency} ${chosen.balance} below threshold ${chosen.threshold}"
            }
        )
        conditions += TriggerCondition(
            "reserve", chosen.reserveOk,
            when {
                chosen.balance == null -> "${chosen.currency} balance not readable"
                chosen.reserveOk ->
                    "${chosen.currency} keeps ${chosen.balance - chosen.cost} after cost ${chosen.cost} (reserve ${chosen.reserve})"
                else ->
                    "${chosen.currency} ${chosen.balance} minus cost ${chosen.cost} breaks reserve ${chosen.reserve}"
            }
        )

        val last = counters.lastVerifiedAt
        val remaining =
            if (last == null) 0L else (config.cooldownMillis - (evidence.now - last)).coerceAtLeast(0L)
        conditions += TriggerCondition(
            "cooldown", remaining == 0L,
            if (remaining == 0L) "cooldown complete" else "cooldown ${remaining / 1000}s remaining"
        )

        val sessionLimit = config.maxUpgradesPerSession
        val sessionOk = sessionLimit == null || evidence.upgradesThisSession < sessionLimit
        conditions += TriggerCondition(
            "session limit", sessionOk,
            if (sessionOk) "session upgrades ${evidence.upgradesThisSession}"
            else "session upgrade limit reached ($sessionLimit)"
        )

        val runLimit = config.maxUpgradesPerRun
        val runUpgrades = counters.upgradesThisRun.coerceAtLeast(0)
        val runOk = runLimit == null || runUpgrades < runLimit
        conditions += TriggerCondition(
            "run limit", runOk,
            if (runOk) "run upgrades $runUpgrades"
            else "run upgrade limit reached ($runLimit)"
        )

        return TriggerEvaluation(conditions)
    }

    private data class CurrencyCheck(
        val currency: String,
        val balance: Long?,
        val threshold: Long,
        val reserve: Long,
        val cost: Long,
        val thresholdOk: Boolean,
        val reserveOk: Boolean
    )
}