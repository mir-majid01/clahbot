package com.clahbot.walls

import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine

/** Auto Walls 2.0 execution states (PRD §8). */
enum class AutoWallsState {
    DISABLED,
    WAITING_FOR_TRIGGER,
    EVALUATING_CONDITIONS,
    OPENING_UPGRADE_LIST,
    SCANNING_WALLS,
    RANKING_CANDIDATES,
    SELECTING_WALL,
    VALIDATING_CONFIRMATION,
    EXECUTING_UPGRADE,
    VERIFYING_RESULT,
    COOLDOWN,
    PAUSED,
    INSUFFICIENT_RESOURCES,
    UNKNOWN_SCREEN,
    SAFETY_BLOCKED,
    EXECUTION_FAILED,
    MANUAL_INTERVENTION_REQUIRED;

    val isTerminal: Boolean
        get() = this in setOf(
            INSUFFICIENT_RESOURCES, UNKNOWN_SCREEN, SAFETY_BLOCKED,
            EXECUTION_FAILED, MANUAL_INTERVENTION_REQUIRED
        )
}

/** Distinct milestones; a tap is never a verified upgrade on its own. */
enum class WallEventStatus {
    DETECTED, SELECTED, ATTEMPTED, CONFIRMATION_VALIDATED, VERIFIED,
    FAILED, SAFETY_STOPPED, INSUFFICIENT, UNKNOWN_SCREEN
}

data class WallEvent(
    val status: WallEventStatus,
    val level: Int,
    val price: Long,
    val currency: String,
    val detail: String = ""
)

data class AutoWallsConfig(
    val enabled: Boolean = false,
    val selection: WallSelectionConfig = WallSelectionConfig(),
    /** Real taps/upgrades stay OFF until device OCR + safety validation pass. */
    val allowTapping: Boolean = false,
    val maxScrolls: Int = 4
)

/** The part of a run that survives a restart (diagnostics only, never resumed). */
data class AutoWallsSnapshot(
    val state: AutoWallsState,
    val candidate: WallCandidate?,
    val scrolls: Int
)

/**
 * The Auto Walls 2.0 workflow as a pure, deterministic state machine (PRD §8).
 * The caller feeds it results (rows, row-tap result, confirmation OCR, confirm
 * result, verification) and executes the returned [Action]. It is:
 *
 *  - **manual-selection only** — it ranks with the user's [WallSelectionConfig];
 *  - **safety-gated** — the confirm button is reached only after
 *    [UpgradeConfirmation] returns Confirm; there is no shortcut;
 *  - **coordinate-free** — candidates come from the latest scan; nothing is
 *    replayed after a scroll, failure or restart;
 *  - **tap-gated** — with [AutoWallsConfig.allowTapping] false it stops before
 *    any tap, so no upgrade happens until the pipeline is device-verified.
 *
 * An optional [Observer] receives every transition and milestone, which is how
 * [AutoWallsSession] persists the run and history.
 */
class AutoWallsMachine(
    private val config: AutoWallsConfig,
    private val observer: Observer? = null
) {

    interface Observer {
        fun onSnapshot(snapshot: AutoWallsSnapshot) {}
        fun onEvent(event: WallEvent) {}
    }

    sealed interface Action {
        object ScanRows : Action
        object ScrollAndScan : Action
        data class TapRow(val candidate: WallCandidate) : Action
        data class ReadConfirmation(val candidate: WallCandidate) : Action
        data class TapConfirm(val candidate: WallCandidate) : Action
        data class Verify(val candidate: WallCandidate) : Action
        object Cooldown : Action
        object WaitForTrigger : Action
        data class Stop(val state: AutoWallsState, val reason: String) : Action
    }

    var state: AutoWallsState =
        if (config.enabled) AutoWallsState.WAITING_FOR_TRIGGER else AutoWallsState.DISABLED
        private set

    /** The exact reason the automation is waiting, or null when the trigger passes. */
    var waitingReason: String? = null
        private set

    /** The user's manual selection priority, exposed for trigger evaluation. */
    val selection: WallSelectionConfig get() = config.selection

    private val _events = mutableListOf<WallEvent>()
    val events: List<WallEvent> get() = _events.toList()

    private var candidate: WallCandidate? = null
    private var scrolls = 0
    /** The scan the current candidate was chosen from; verification compares against it. */
    private var lastRows: List<WallCandidate> = emptyList()

    fun snapshot(): AutoWallsSnapshot = AutoWallsSnapshot(state, candidate, scrolls)

    private fun enter(next: AutoWallsState) {
        state = next
        observer?.onSnapshot(snapshot())
    }

    private fun emit(event: WallEvent) {
        _events += event
        observer?.onEvent(event)
    }

    fun enable(): Action {
        if (state == AutoWallsState.DISABLED) enter(AutoWallsState.WAITING_FOR_TRIGGER)
        return Action.WaitForTrigger
    }

    fun disable(): Action {
        candidate = null
        waitingReason = null
        enter(AutoWallsState.DISABLED)
        return Action.WaitForTrigger
    }

    /**
     * Feeds the Combined-AND trigger result (PRD §2.1/§4). The machine is the
     * single execution authority: discovery begins only when [evaluation] passes
     * every condition; otherwise it keeps waiting and surfaces the exact reason.
     * Tapping stays gated by [AutoWallsConfig.allowTapping] downstream.
     */
    fun onTrigger(evaluation: TriggerEvaluation): Action {
        if (state != AutoWallsState.WAITING_FOR_TRIGGER) return unexpected()
        waitingReason = evaluation.waitingReason
        if (!evaluation.pass) return Action.WaitForTrigger
        scrolls = 0
        candidate = null
        lastRows = emptyList()
        enter(AutoWallsState.SCANNING_WALLS)
        return Action.ScanRows
    }

    fun onRows(rows: List<WallCandidate>): Action {
        if (state != AutoWallsState.SCANNING_WALLS) return unexpected()
        lastRows = rows
        enter(AutoWallsState.RANKING_CANDIDATES)
        val selected = WallSelection.select(rows, config.selection)
        if (selected == null) {
            if (scrolls < config.maxScrolls) {
                scrolls++
                enter(AutoWallsState.SCANNING_WALLS)
                return Action.ScrollAndScan
            }
            enter(AutoWallsState.SAFETY_BLOCKED)
            return Action.Stop(state, "no eligible wall after scanning")
        }
        candidate = selected
        enter(AutoWallsState.SELECTING_WALL)
        emit(WallEvent(WallEventStatus.DETECTED, selected.level, selected.price, selected.currency))
        emit(WallEvent(WallEventStatus.SELECTED, selected.level, selected.price, selected.currency))
        if (!config.allowTapping) {
            enter(AutoWallsState.SAFETY_BLOCKED)
            return Action.Stop(state, "tapping is disabled until device verification")
        }
        enter(AutoWallsState.VALIDATING_CONFIRMATION)
        return Action.TapRow(selected)
    }

    fun onRowTapResult(ok: Boolean): Action {
        if (state != AutoWallsState.VALIDATING_CONFIRMATION) return unexpected()
        val c = candidate ?: return unexpected()
        if (!ok) {
            enter(AutoWallsState.EXECUTION_FAILED)
            emit(WallEvent(WallEventStatus.FAILED, c.level, c.price, c.currency, "row tap failed"))
            return Action.Stop(state, "row tap failed")
        }
        return Action.ReadConfirmation(c)
    }

    fun onConfirmation(lines: List<OcrLine>, currencyAt: (OcrBox) -> String? = { null }): Action {
        if (state != AutoWallsState.VALIDATING_CONFIRMATION) return unexpected()
        val c = candidate ?: return unexpected()
        if (lines.isEmpty()) {
            enter(AutoWallsState.UNKNOWN_SCREEN)
            emit(
                WallEvent(
                    WallEventStatus.UNKNOWN_SCREEN, c.level, c.price, c.currency,
                    "no confirmation dialog"
                )
            )
            return Action.Stop(state, "no confirmation dialog")
        }
        return when (val r = UpgradeConfirmation.validate(lines, c, currencyAt)) {
            is UpgradeConfirmation.Result.Refuse -> {
                val (next, status) = mapRefusal(r.reason)
                enter(next)
                emit(WallEvent(status, c.level, c.price, c.currency, r.reason))
                Action.Stop(next, r.reason)
            }

            is UpgradeConfirmation.Result.Confirm -> {
                emit(
                    WallEvent(
                        WallEventStatus.CONFIRMATION_VALIDATED, c.level, r.price, r.currency
                    )
                )
                if (!config.allowTapping) {
                    enter(AutoWallsState.SAFETY_BLOCKED)
                    return Action.Stop(state, "tapping is disabled until device verification")
                }
                emit(WallEvent(WallEventStatus.ATTEMPTED, c.level, r.price, r.currency))
                enter(AutoWallsState.EXECUTING_UPGRADE)
                Action.TapConfirm(c)
            }
        }
    }

    fun onConfirmTapResult(ok: Boolean): Action {
        if (state != AutoWallsState.EXECUTING_UPGRADE) return unexpected()
        val c = candidate ?: return unexpected()
        if (!ok) {
            enter(AutoWallsState.EXECUTION_FAILED)
            emit(WallEvent(WallEventStatus.FAILED, c.level, c.price, c.currency, "confirm tap failed"))
            return Action.Stop(state, "confirm tap failed")
        }
        // A successful tap is NOT an upgrade yet — verify first.
        enter(AutoWallsState.VERIFYING_RESULT)
        return Action.Verify(c)
    }

    /**
     * Feeds the post-tap re-scan (and optional counter readings). Only
     * [UpgradeVerification] may call an upgrade verified; the tap itself never
     * does. The verdict and its evidence/reason go into history.
     */
    fun onVerification(
        after: List<WallCandidate>,
        resourcesBefore: WallCounters.Reading? = null,
        resourcesAfter: WallCounters.Reading? = null
    ): Action {
        if (state != AutoWallsState.VERIFYING_RESULT) return unexpected()
        val c = candidate ?: return unexpected()
        when (val r = UpgradeVerification.verify(lastRows, c, after, resourcesBefore, resourcesAfter)) {
            is UpgradeVerification.Result.Verified ->
                emit(WallEvent(WallEventStatus.VERIFIED, c.level, c.price, c.currency, r.detail))

            is UpgradeVerification.Result.Unverified ->
                emit(
                    WallEvent(
                        WallEventStatus.FAILED, c.level, c.price, c.currency,
                        "attempted but unverified: ${r.reason}"
                    )
                )
        }
        enter(AutoWallsState.COOLDOWN)
        return Action.Cooldown
    }

    fun onCooldownElapsed(): Action {
        if (state != AutoWallsState.COOLDOWN) return unexpected()
        candidate = null
        enter(AutoWallsState.WAITING_FOR_TRIGGER)
        return Action.WaitForTrigger
    }

    /**
     * §5.5 repeat: after a verified upgrade another wall may go up in the same
     * visit without waiting for more attacks. The caller gates this on
     * [WallLogic.mayRepeatUpgrade] (proven spend, user allowance, ceiling); the
     * machine only re-opens a fresh scan and never replays the old candidate.
     */
    fun onRepeatUpgrade(): Action {
        if (state != AutoWallsState.COOLDOWN) return unexpected()
        scrolls = 0
        candidate = null
        lastRows = emptyList()
        enter(AutoWallsState.SCANNING_WALLS)
        return Action.ScanRows
    }

    /** The caller reports an OCR/screen timeout or failure for the current step. */
    fun onTimeout(reason: String): Action {
        if (state.isTerminal) return unexpected()
        val c = candidate
        emit(
            WallEvent(
                WallEventStatus.FAILED,
                c?.level ?: 0,
                c?.price ?: 0L,
                c?.currency ?: "unknown",
                reason
            )
        )
        val next = if (c == null) AutoWallsState.UNKNOWN_SCREEN else AutoWallsState.EXECUTION_FAILED
        enter(next)
        return Action.Stop(next, reason)
    }

    private fun mapRefusal(reason: String): Pair<AutoWallsState, WallEventStatus> = when {
        reason.contains("insufficient") -> AutoWallsState.INSUFFICIENT_RESOURCES to WallEventStatus.INSUFFICIENT
        reason.contains("premium") -> AutoWallsState.SAFETY_BLOCKED to WallEventStatus.SAFETY_STOPPED
        reason.contains("mismatch") -> AutoWallsState.EXECUTION_FAILED to WallEventStatus.FAILED
        else -> AutoWallsState.UNKNOWN_SCREEN to WallEventStatus.UNKNOWN_SCREEN
    }

    private fun unexpected(): Action = Action.Stop(state, "unexpected step while $state")
}
