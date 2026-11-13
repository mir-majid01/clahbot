package com.clahbot.walls

import com.clahbot.execution.MacroTimeline
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.ResourceKind

/**
 * One captured game frame plus everything the wall pipeline reads from it: the
 * classified screen, the OCR lines, the frame geometry and the resource-icon
 * classifier. The classifier is injected so the whole pipeline stays JVM-testable
 * with a fake; the Android adapter supplies the real icon sampler.
 */
class WallFrame(
    val observation: ScreenObservation,
    val lines: List<OcrLine>,
    val width: Int,
    val height: Int,
    val classify: (OcrBox) -> ResourceKind?
) {
    /** Currency of a price box, derived from the same icon classifier. */
    val currencyAt: (OcrBox) -> String? = { box ->
        when (classify(box)) {
            ResourceKind.GOLD -> "gold"
            ResourceKind.ELIXIR -> "elixir"
            ResourceKind.DARK -> "dark"
            null -> null
        }
    }

    fun counters(): WallCounters.Reading = WallCounters.read(lines, width, height, classify)

    fun candidates(): List<WallCandidate> =
        WallDiscovery.discover(lines, height, currencyAt = currencyAt)
}

/**
 * The thin device seam the runtime drives. The real adapter wraps the shared
 * capture/OCR/classify pass, the accessibility gesture dispatcher and a scroll;
 * a fake serves frames and records taps so every stage is testable on the JVM.
 */
interface WallDevice {
    suspend fun observe(): WallFrame?
    suspend fun tap(box: ScreenBox): Boolean
    suspend fun scroll(): Boolean
    suspend fun wait(ms: Long)
    fun now(): Long
}

/**
 * The reserve re-check immediately before the confirm tap (PRD §5.4). It uses the
 * actual discounted confirmation price, never the list price, and refuses an
 * unreadable balance or price rather than guessing.
 */
object AutoWallsReserve {
    data class Result(val ok: Boolean, val reason: String)

    fun recheck(balance: Long?, reserve: Long, actualCost: Long, currency: String): Result {
        if (balance == null) return Result(false, "$currency balance not readable before confirm")
        if (actualCost <= 0L) return Result(false, "confirm price not readable")
        val remaining = balance - actualCost
        return if (remaining >= reserve) {
            Result(true, "$currency keeps $remaining after cost $actualCost (reserve $reserve)")
        } else {
            Result(false, "$currency $balance minus cost $actualCost breaks reserve $reserve")
        }
    }
}

/**
 * Drives [AutoWallsMachine] against a live [WallDevice] (Auto Walls 2.0 runtime,
 * PRD §3–§9). The machine remains the single execution authority: this class only
 * captures frames, relocates the selected wall on the current scan, dispatches the
 * gestures the machine asks for, re-checks the reserve against the confirmed price,
 * and feeds the post-upgrade re-scan back for verification.
 *
 * Safety is never bypassed here:
 *  - with [AutoWallsConfig.allowTapping] false the machine stops at SAFETY_BLOCKED
 *    before any tap, so production runs discover and select but never upgrade;
 *  - a successful gesture is never treated as an upgrade — only
 *    [UpgradeVerification] may mark one verified, and only a verified upgrade
 *    feeds the counters;
 *  - an unsafe screen, a never-press overlap, a stale row or an unreadable balance
 *    stops the run before the corresponding tap.
 */
class AutoWallsRuntime(
    private val session: AutoWallsSession,
    private val device: WallDevice,
    private val speed: Float = 1f,
    private val allowRepeat: Boolean = false,
    private val maxRepeats: Int = DEFAULT_MAX_REPEATS,
    private val log: (String) -> Unit = {}
) {

    data class Outcome(val state: AutoWallsState, val reason: String, val verifiedUpgrades: Int)

    private var verified = 0
    private var sessionUpgrades = 0
    private var repeats = 0
    private var lastVerified = false

    /** §5.6 execution speed: scale every inter-action delay like a macro gap. */
    fun delayMs(baseMs: Long): Long = MacroTimeline.gapMillis(baseMs, speed)

    /** A genuinely completed attack (results-screen record) feeds the trigger counter. */
    fun onAttackCompleted() {
        val state = session.loadTriggerState()
        session.saveTriggerState(state.copy(counters = state.counters.onAttackCompleted()))
    }

    private fun feedVerifiedUpgrade(now: Long) {
        val state = session.loadTriggerState()
        session.saveTriggerState(state.copy(counters = state.counters.onVerifiedUpgrade(now)))
        sessionUpgrades++
    }

    suspend fun run(manual: Boolean = false): Outcome {
        val machine = session.machine
            ?: return Outcome(AutoWallsState.DISABLED, "session not started", verified)
        if (machine.state == AutoWallsState.DISABLED) {
            return Outcome(AutoWallsState.DISABLED, "Auto Walls is switched off", verified)
        }

        var action: AutoWallsMachine.Action = AutoWallsMachine.Action.WaitForTrigger
        var lastScan: WallFrame? = null
        var beforeResources: WallCounters.Reading? = null

        while (true) {
            action = when (action) {
                is AutoWallsMachine.Action.WaitForTrigger -> {
                    val evaluation = if (manual) {
                        // A manual "Run now" bypasses the trigger counters; the
                        // machine's own enabled guard still applies above.
                        TriggerEvaluation(
                            listOf(TriggerCondition("manual", true, "manual run requested"))
                        )
                    } else {
                        val frame = device.observe()
                            ?: return finish(machine, "no frame to evaluate the trigger")
                        val evidence = TriggerEvidence(
                            enabled = machine.state != AutoWallsState.DISABLED,
                            resources = frame.counters(),
                            now = device.now(),
                            upgradesThisSession = sessionUpgrades
                        )
                        session.evaluateTrigger(evidence)
                    }
                    val next = machine.onTrigger(evaluation)
                    if (!evaluation.pass) {
                        return Outcome(
                            AutoWallsState.WAITING_FOR_TRIGGER,
                            machine.waitingReason ?: "waiting for the trigger",
                            verified
                        )
                    }
                    next
                }

                is AutoWallsMachine.Action.ScanRows -> {
                    val frame = device.observe() ?: return finish(machine, "no frame to scan")
                    lastScan = frame
                    beforeResources = frame.counters()
                    machine.onRows(frame.candidates())
                }

                is AutoWallsMachine.Action.ScrollAndScan -> {
                    if (!device.scroll()) return finish(machine, "scroll failed")
                    device.wait(delayMs(SETTLE_MS))
                    val frame = device.observe() ?: return finish(machine, "no frame after scroll")
                    lastScan = frame
                    beforeResources = frame.counters()
                    machine.onRows(frame.candidates())
                }

                is AutoWallsMachine.Action.TapRow -> {
                    val frame = lastScan ?: return finish(machine, "no scan to locate the wall")
                    if (WallLogic.unsafeDuringRun(frame.observation.state)) {
                        return finish(machine, "screen changed before the row tap")
                    }
                    val box = WallDiscovery.tapBox(
                        action.candidate, frame.lines, frame.height, currencyAt = frame.currencyAt
                    )?.let { ScreenBox.fromOcr(it, frame.width, frame.height) }
                        ?: return finish(machine, "wall row not located on the current scan")
                    if (frame.observation.forbidden.any { it.intersects(box) }) {
                        return finish(machine, "wall row overlaps a never-press box")
                    }
                    device.wait(delayMs(STEP_MS))
                    machine.onRowTapResult(device.tap(box))
                }

                is AutoWallsMachine.Action.ReadConfirmation -> {
                    device.wait(delayMs(SETTLE_MS))
                    val frame = device.observe() ?: return finish(machine, "no confirmation frame")
                    machine.onConfirmation(frame.lines, frame.currencyAt)
                }

                is AutoWallsMachine.Action.TapConfirm -> {
                    val frame = device.observe() ?: return finish(machine, "no frame before confirm")
                    if (WallLogic.unsafeDuringRun(frame.observation.state)) {
                        return finish(machine, "screen changed before confirm")
                    }
                    // Re-validate the dialog on the freshest frame right before tapping.
                    val confirmed = UpgradeConfirmation.validate(frame.lines, action.candidate, frame.currencyAt)
                    if (confirmed !is UpgradeConfirmation.Result.Confirm) {
                        return finish(machine, (confirmed as UpgradeConfirmation.Result.Refuse).reason)
                    }
                    val config = session.loadTriggerState().config
                    // The dialog's own price line is not a balance; exclude it so the
                    // reserve is checked against the live counter strip only.
                    val balanceLines = frame.lines.filter { it.box != confirmed.priceBox }
                    val reading = WallCounters.read(balanceLines, frame.width, frame.height, frame.classify)
                    val gold = action.candidate.currency == "gold"
                    val balance = if (gold) reading.gold else reading.elixir
                    val reserve = if (gold) config.goldReserve else config.elixirReserve
                    val check = AutoWallsReserve.recheck(balance, reserve, confirmed.price, action.candidate.currency)
                    if (!check.ok) return finish(machine, check.reason)
                    val target = confirmTarget(frame) ?: return finish(machine, "confirm button not located")
                    if (frame.observation.forbidden.any { it.intersects(target) }) {
                        return finish(machine, "confirm button overlaps a never-press box")
                    }
                    device.wait(delayMs(STEP_MS))
                    machine.onConfirmTapResult(device.tap(target))
                }

                is AutoWallsMachine.Action.Verify -> {
                    device.wait(delayMs(SETTLE_MS))
                    val frame = device.observe() ?: return finish(machine, "no frame to verify")
                    val next = machine.onVerification(frame.candidates(), beforeResources, frame.counters())
                    if (machine.events.lastOrNull()?.status == WallEventStatus.VERIFIED) {
                        verified++
                        lastVerified = true
                        feedVerifiedUpgrade(device.now())
                    } else {
                        lastVerified = false
                    }
                    next
                }

                is AutoWallsMachine.Action.Cooldown -> {
                    val config = session.loadTriggerState().config
                    device.wait(delayMs(config.cooldownMillis))
                    if (WallLogic.mayRepeatUpgrade("ran", allowRepeat, lastVerified, repeats, maxRepeats)) {
                        repeats++
                        machine.onRepeatUpgrade()
                    } else {
                        machine.onCooldownElapsed()
                    }
                }

                is AutoWallsMachine.Action.Stop -> return Outcome(action.state, action.reason, verified)
            }
        }
    }

    private fun confirmTarget(frame: WallFrame): ScreenBox? {
        frame.observation.confirmButton?.let { return it }
        val line = frame.lines.firstOrNull { AutoWallsSafety.normalize(it.text).contains("confirm") }
            ?: return null
        val box = line.box ?: return null
        return ScreenBox.fromOcr(box, frame.width, frame.height)
    }

    private fun finish(machine: AutoWallsMachine, reason: String): Outcome {
        val stop = machine.onTimeout(reason)
        val state = (stop as? AutoWallsMachine.Action.Stop)?.state ?: machine.state
        log("auto-walls stop: $reason")
        return Outcome(state, reason, verified)
    }

    companion object {
        /** Pause for a screen transition to settle before reading it. */
        const val SETTLE_MS = 1200L

        /** Pause between locating a target and tapping it. */
        const val STEP_MS = 350L

        const val DEFAULT_MAX_REPEATS = 5
    }
}