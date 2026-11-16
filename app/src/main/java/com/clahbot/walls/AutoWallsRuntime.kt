package com.clahbot.walls

import com.clahbot.execution.MacroTimeline
import com.clahbot.game.GameState
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import com.clahbot.gesture.GestureAuthority
import com.clahbot.gesture.ScanGate
import com.clahbot.ocr.BuilderFaceKind
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
    /** System.currentTimeMillis() when the source frame was captured. */
    val capturedAtMillis: Long = 0L,
    val classify: (OcrBox) -> ResourceKind?
) {
    /**
     * Reads the builder face above a fraction badge. The real adapter installs
     * a bitmap sampler; tests can pass a stub. The default returns null, which
     * makes [BuilderDetection] report UNCERTAIN rather than guess a slot.
     */
    var builderFaceAt: (OcrBox) -> BuilderFaceKind? = { null }

    /**
     * Optional diagnostic of the face sampler (template scores and geometry).
     * Only the Android adapter installs it; it is used for the persistent scan
     * log so a wrong builder count can be explained from the log alone.
     */
    var builderFaceDetail: () -> String = { "" }

    /** Currency of a price box, derived from the same icon classifier. */
    val currencyAt: (OcrBox) -> String? = { box ->
        when (classify(box)) {
            ResourceKind.GOLD -> "gold"
            ResourceKind.ELIXIR -> "elixir"
            ResourceKind.DARK -> "dark"
            null -> null
        }
    }

    fun counters(previous: WallCounters.Reading? = null): WallCounters.Reading {
        // The gold/elixir counters are in the fixed HUD above the list. On the
        // Buildings & Heroes screen the header marks where the rows begin;
        // otherwise the HUD strip at the very top is the only place a balance
        // can be. Without this cut the first list rows' prices — which sit high
        // on a tall frame — could be read as balances (the 16,000,000 bug).
        val menuTop = WallMenu.inspect(lines, height)
            .takeIf { it.headerDetected }
            ?.listTop
            ?.takeIf { it > 0 }
            ?: (height * COUNTER_STRIP_FRACTION).toInt()
        val scoped = if (menuTop <= 0) {
            lines
        } else {
            lines.filter { (it.box?.bottom ?: 0) <= menuTop }
        }
        return WallCounters.read(scoped, width, height, classify, previous)
    }

    fun candidates(): List<WallCandidate> =
        WallDiscovery.discover(lines, height, currencyAt = currencyAt)

    private companion object {
        /** Share of the frame height the top resource HUD can occupy. */
        const val COUNTER_STRIP_FRACTION = 0.12f
    }
}

/**
 * The thin device seam the runtime drives. The real adapter wraps the shared
 * capture/OCR/classify pass, the accessibility gesture dispatcher and a scroll;
 * a fake serves frames and records taps so every stage is testable on the JVM.
 */
interface WallDevice {
    suspend fun observe(): WallFrame?

    /**
     * Captures the SAME screen again, for a re-read of a field that did not come
     * out cleanly (the builder strip's fraction, a level badge, a price).
     *
     * A re-read is a fresh screenshot of the frame already on screen: it must not
     * advance a scripted sequence of screens. That is why it is a separate seam —
     * a JVM fake serves a queue of screens to model the scan, and a re-read that
     * silently consumed the next entry would make every scripted test lie about
     * what the scanner saw.
     */
    suspend fun observeAgain(): WallFrame? = observe()

    suspend fun tap(box: ScreenBox): Boolean
    suspend fun scroll(): Boolean

    /**
     * Scrolls the list back up by at most [steps] controlled swipes to restore
     * the position the scan started from. Returns how many reverse swipes were
     * actually performed. Cancellation-safe callers invoke this from a
     * NonCancellable finally block.
     */
    suspend fun scrollBack(steps: Int): Int = 0

    /**
     * One corrective upward swipe inside the list panel, used when the
     * post-restore signature does not match the pre-scan top signature.
     * Returns false when the gesture could not be dispatched.
     *
     * The scanner goes through this seam instead of calling the accessibility
     * service directly, so the JVM tests can drive the restore path.
     */
    suspend fun scrollUp(): Boolean = scrollBack(1) > 0

    suspend fun wait(ms: Long)
    fun now(): Long
    /** Release exclusive wall-upgrade ownership, if any. Safe to call when idle. */
    fun endWallTxn()

    /**
     * Id of the scan that currently owns the screen.
     *
     * The scanner sets it right after it takes the [ScanGate]; the device uses
     * it as the gesture session id so the shared [GestureAuthority] recognises
     * the scan's own swipe while the gate is held and refuses every other
     * automation surface.
     */
    var scanOwnerId: Long
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
        var lastBuilderEvidence: BuilderEvidence? = null

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
                        // Phase 5C: capture builder evidence on the village (HOME)
                        // screen before any upgrade attempt begins.
                        if (frame.observation.state == GameState.HOME) {
                            val reading = BuilderDetection.detect(
                                frame.lines,
                                frame.width,
                                frame.height,
                                frame.builderFaceAt
                            )
                            lastBuilderEvidence = BuilderEvidence.Validated.fromFrame(
                                reading = reading,
                                wallFrame = frame,
                                screenState = frame.observation.state
                            )
                        }
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
                        device.endWallTxn()
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
                    // Builder evidence is captured on the HOME screen in
                    // WaitForTrigger; do not overwrite it here (this may be the
                    // wall-list screen). Keep existing evidence for TapRow.
                    machine.onRows(frame.candidates())
                }

                is AutoWallsMachine.Action.ScrollAndScan -> {
                    if (!device.scroll()) return finish(machine, "scroll failed")
                    device.wait(delayMs(SETTLE_MS))
                    val frame = device.observe() ?: return finish(machine, "no frame after scroll")
                    lastScan = frame
                    beforeResources = frame.counters()
                    // Builder evidence already captured on HOME screen in
                    // WaitForTrigger; keep it for TapRow.
                    machine.onRows(frame.candidates())
                }

                is AutoWallsMachine.Action.TapRow -> {
                    val frame = lastScan ?: return finish(machine, "no scan to locate the wall")
                    if (WallLogic.unsafeDuringRun(frame.observation.state)) {
                        return finish(machine, "screen changed before the row tap")
                    }
                    // Phase 5C: builder availability re-check right before the tap.
                    // Fail-closed: if the gate is off or evidence is stale/uncertain, stop.
                    if (lastBuilderEvidence == null || !lastBuilderEvidence.authorizesUpgrade()) {
                        return finish(machine, "builder not available or validation disabled")
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

                is AutoWallsMachine.Action.Stop -> {
                    device.endWallTxn()
                    return Outcome(action.state, action.reason, verified)
                }
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
        device.endWallTxn()
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