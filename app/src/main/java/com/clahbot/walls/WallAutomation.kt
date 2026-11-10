package com.clahbot.walls

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.SystemClock
import com.clahbot.data.AppEventLog
import com.clahbot.data.BotState
import com.clahbot.data.ResultToastUi
import com.clahbot.data.WallStripUi
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.execution.MacroTimeline
import com.clahbot.game.GameState
import com.clahbot.game.ScreenObservation
import com.clahbot.ocr.BitmapIconColorSampler
import com.clahbot.recovery.RecoveryAgent
import com.clahbot.results.AppResultsReader
import com.clahbot.service.ClahAccessibilityService
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the user's recorded wall-upgrade macro at the right moment and nothing
 * else. It never generates wall coordinates: the taps are the ones the user
 * recorded. Safety beats completion — an unexpected dialog, a recorded tap that
 * would land on a gem or Buy control, or an unknown screen stops the run at
 * once, and the attack loop only resumes after the village is verified safe.
 */
class WallAutomation(
    private val context: Context,
    private val agent: RecoveryAgent,
    private val reader: AppResultsReader,
    private val repository: WallsRepository,
    private val macroProvider: suspend (Long) -> Macro?,
    private val notify: (kind: ResultToastUi.Kind, message: String) -> Unit
) {

    data class Outcome(
        val status: String,
        val detail: String,
        val trigger: WallTrigger = WallTrigger.NONE,
        val gold: Long? = null,
        val elixir: Long? = null,
        val macroName: String? = null,
        val safeToResume: Boolean = true
    )

    enum class Mode {
        /** The attack loop reached the village and Auto Walls is on. */
        AUTOMATIC,

        /** Run now, under the user's eye. This is what validates a new macro. */
        MANUAL,

        /** Test macro: reads and decides, never taps. */
        TEST;

        val taps: Boolean get() = this != TEST
        val needsResources: Boolean get() = this != MANUAL
    }

    @Volatile
    private var busy = false

    @Volatile
    private var stopRequested = false

    private var upgradedThisVisit = false
    private var lastUpgradeVerified = false
    private var lastRunAt = 0L

    fun isBusy(): Boolean = busy

    /** Leaving the village arms the next visit for one upgrade. */
    fun noteLeftHome() {
        upgradedThisVisit = false
        lastUpgradeVerified = false
    }

    /** The attack loop boundary: upgrade only when the settings allow it. */
    suspend fun onHomeVillage(attackMacro: Macro?): Outcome {
        val settings = repository.settings()
        if (!settings.enabled) return Outcome("skipped", "Auto Walls is off")
        if (upgradedThisVisit && !settings.allowRepeatUpgrades) {
            return Outcome("skipped", "already upgraded on this home visit")
        }
        if (upgradedThisVisit && !lastUpgradeVerified) {
            // Repeat upgrades need proof the previous one actually spent.
            return Outcome("skipped", "the previous upgrade was not verified by a balance drop")
        }
        if (System.currentTimeMillis() - lastRunAt < COOLDOWN_MS) {
            return Outcome("skipped", "cooldown after the last upgrade")
        }
        var outcome = run(settings, attackMacro, Mode.AUTOMATIC)
        var repeats = 0
        while (WallLogic.mayRepeatUpgrade(
                status = outcome.status,
                allowed = settings.allowRepeatUpgrades,
                verified = lastUpgradeVerified,
                repeats = repeats,
                maxRepeats = MAX_REPEAT_UPGRADES
            ) && !stopRequested
        ) {
            // Same visit, and the last spend is proven by a balance drop, so one
            // more wall may go up. Each pass re-reads the village and the counters.
            repeats++
            upgradedThisVisit = false
            AppEventLog.log("walls: repeat upgrade $repeats of $MAX_REPEAT_UPGRADES")
            outcome = run(repository.settings(), attackMacro, Mode.AUTOMATIC)
        }
        return outcome
    }

    /** Manual Run now: a verified home village is enough, no threshold check. */
    suspend fun runManually(attackMacro: Macro?): Outcome =
        run(repository.settings(), attackMacro, Mode.MANUAL)

    /** Test macro: read the counters, decide, report — never tap. */
    suspend fun dryRun(attackMacro: Macro?): Outcome =
        run(repository.settings(), attackMacro, Mode.TEST)

    fun cancel() {
        stopRequested = true
    }

    private suspend fun run(
        settings: WallSettings,
        attackMacro: Macro?,
        mode: Mode
    ): Outcome {
        if (busy) return Outcome("skipped", "a wall run is already in progress")
        busy = true
        stopRequested = false
        // The strip goes up before the village wait and the two counter reads, so
        // "a run is in progress" is something the user can see and stop. Without
        // it a pending run looked like a stale lock: nothing on screen, and every
        // further Test/Run answered "already going".
        BotState.setWallStrip(
            WallStripUi(if (mode == Mode.TEST) "Walls · checking" else "Walls · waiting for the village")
        )
        try {
            val home = waitForHome()
            if (stopRequested) return Outcome("stopped", "stopped by you")
            if (home == null) return Outcome("skipped", "Clash is not on the home village")
            val macro = when (val lookup = resolveMacro(settings, attackMacro)) {
                is MacroLookup.Found -> lookup.macro
                is MacroLookup.Missing -> {
                    report(mode, lookup.message)
                    return Outcome("skipped", lookup.message)
                }
            }
            if (macro.kind != Macro.KIND_WALL) {
                val message = "Macro \"${macro.name}\" is not a wall upgrade macro"
                report(mode, message)
                return Outcome("skipped", message, macroName = macro.name)
            }
            if (macro.gestures.isEmpty()) {
                return Outcome("skipped", "Wall macro \"${macro.name}\" has no gestures")
            }
            val tested = settings.isTested(macro.id)
            if (mode == Mode.AUTOMATIC && !tested) {
                // A macro recorded moments ago is never spent automatically.
                AppEventLog.log("walls: \"${macro.name}\" has not been run under supervision yet")
                return Outcome(
                    "skipped",
                    "Run the wall macro once with Run now before automating it",
                    macroName = macro.name
                )
            }

            val before = readBalances(home.frame.bitmap)
            val second = agent.observe() ?: return Outcome("skipped", "no game frame")
            val afterFirstRead = readBalances(second.frame.bitmap)
            val gold = if (WallLogic.stable(before.gold, afterFirstRead.gold)) {
                afterFirstRead.gold
            } else {
                null
            }
            val elixir = if (WallLogic.stable(before.elixir, afterFirstRead.elixir)) {
                afterFirstRead.elixir
            } else {
                null
            }

            if (mode.needsResources && gold == null && elixir == null) {
                return Outcome(
                    "skipped",
                    "The counters did not read the same value twice · ${lastReading.describe()}"
                )
            }

            val trigger = WallLogic.choose(
                gold = gold,
                elixir = elixir,
                cost = settings.cost(),
                reserve = settings.reserve(),
                mode = settings.resourceMode,
                priority = settings.priority
            )
            if (mode.needsResources && !trigger.isRun) {
                val reason = if (settings.cost() <= 0L) {
                    "Set the cost per wall first"
                } else {
                    "Neither resource covers the cost after the reserve"
                }
                return Outcome("skipped", reason, gold = gold, elixir = elixir)
            }
            val chosen = if (trigger.isRun) trigger else WallTrigger.GOLD

            val unsafe = WallLogic.unsafeTaps(macro, second.observation)
            if (unsafe.isNotEmpty()) {
                AppEventLog.log(
                    "walls: never-press refusal — " +
                        WallLogic.describeForbidden(
                            second.observation,
                            WallLogic.firstForbiddenHit(macro, second.observation)
                        )
                )
                // A dry run reports the same refusal but writes no history entry:
                // nothing was run, so nothing may look like an upgrade attempt.
                val message = if (mode.taps) {
                    "Wall macro ${unsafe.first()}"
                } else {
                    "Dry run: Wall macro ${unsafe.first()}"
                }
                if (mode.taps) {
                    log(macro, chosen, gold, elixir, null, null, "stopped", message)
                }
                notify(ResultToastUi.Kind.WARNING, message)
                return Outcome("stopped", message, chosen, gold, elixir, macro.name)
            }

            if (mode == Mode.TEST) {
                val message = "Dry run: would run \"${macro.name}\" paying from " +
                    chosen.resourceName() + describe(gold, elixir, settings) +
                    if (tested) "" else " · run it once with Run now first"
                notify(ResultToastUi.Kind.SUCCESS, message)
                return Outcome("dry-run", message, chosen, gold, elixir, macro.name)
            }

            BotState.setWallStrip(WallStripUi("Walls · ${chosen.resourceName()}"))
            AppEventLog.log("walls: running macro \"${macro.name}\" from $chosen")
            val stopReason = replay(macro)
            BotState.setWallStrip(null)

            if (mode == Mode.MANUAL && stopReason == null) {
                // The user watched this macro play end to end; automation may use it.
                repository.updateSettings { it.copy(testedWallMacroId = macro.id) }
            }

            if (stopReason != null) {
                log(macro, chosen, gold, elixir, null, null, "stopped", stopReason)
                notify(ResultToastUi.Kind.WARNING, "Wall macro stopped: $stopReason")
                val safe = verifySafeAfterRun()
                return Outcome(
                    "stopped",
                    stopReason,
                    chosen,
                    gold,
                    elixir,
                    macro.name,
                    safeToResume = safe
                )
            }

            val settled = agent.observe()
            val afterGold = settled?.let { readBalances(it.frame.bitmap).gold }
            val afterElixir = settled?.let { readBalances(it.frame.bitmap).elixir }
            val safe = verifySafeAfterRun()
            val spentGold = spend(gold, afterGold)
            val spentElixir = spend(elixir, afterElixir)
            // A wall went up only if one of the counters actually went down.
            lastUpgradeVerified = (spentGold ?: 0L) > 0L || (spentElixir ?: 0L) > 0L
            val detail = "ran \"${macro.name}\"" +
                spentGold?.let { " · ${compact(it)} gold" }.orEmpty() +
                spentElixir?.let { " · ${compact(it)} elixir" }.orEmpty() +
                if (safe) "" else " · village not verified"
            log(macro, chosen, gold, elixir, afterGold, afterElixir, if (safe) "ran" else "stopped", detail)
            upgradedThisVisit = true
            lastRunAt = System.currentTimeMillis()
            AppEventLog.log("walls: $detail")
            notify(
                if (safe) ResultToastUi.Kind.SUCCESS else ResultToastUi.Kind.WARNING,
                if (safe) "Walls upgraded" else "Wall macro ended on an unsafe screen"
            )
            return Outcome(
                if (safe) "ran" else "stopped",
                detail,
                chosen,
                gold,
                elixir,
                macro.name,
                safeToResume = safe
            )
        } finally {
            busy = false
            BotState.setWallStrip(null)
        }
    }

    // ---------- screening ----------

    /**
     * A refusal at a loop boundary would toast on every loop, so automatic
     * problems go to the event log and the Walls status line instead.
     */
    private fun report(mode: Mode, message: String) {
        if (mode == Mode.AUTOMATIC) {
            AppEventLog.log("walls: $message")
        } else {
            notify(ResultToastUi.Kind.WARNING, message)
        }
    }

    private suspend fun waitForHome(): RecoveryAgent.Reading? {
        var waited = 0L
        while (waited < HOME_WAIT_MS) {
            if (stopRequested) return null
            val reading = agent.observe() ?: return null
            when (reading.observation.state) {
                GameState.HOME -> {
                    // Read again so the village has settled before OCR.
                    delay(SETTLE_MS)
                    val settled = agent.observe() ?: return reading
                    if (settled.observation.state == GameState.HOME) return settled
                }

                GameState.LOADING, GameState.NOT_CLASH -> Unit
                else -> if (WallLogic.unsafeDuringRun(reading.observation.state)) return null
            }
            delay(POLL_MS)
            waited += POLL_MS
        }
        return null
    }

    private sealed class MacroLookup {
        class Found(val macro: Macro) : MacroLookup()
        class Missing(val message: String) : MacroLookup()
    }

    /**
     * The attack's linked macro wins; the global Walls choice is used only when
     * the attack links nothing. A linked macro that no longer exists is an
     * error, never a silent substitution.
     */
    private suspend fun resolveMacro(settings: WallSettings, attackMacro: Macro?): MacroLookup {
        val linkedId = attackMacro?.linkedWallMacroId ?: -1L
        if (linkedId > 0) {
            val linked = macroProvider(linkedId)
            return if (linked == null) {
                MacroLookup.Missing(
                    "The wall macro linked to \"${attackMacro?.name}\" is gone. " +
                        "Re-link it or pick one in Walls."
                )
            } else {
                MacroLookup.Found(linked)
            }
        }
        if (settings.wallMacroId <= 0L) {
            return MacroLookup.Missing("Record a wall upgrade macro in the Walls tab first.")
        }
        return macroProvider(settings.wallMacroId)?.let { MacroLookup.Found(it) }
            ?: MacroLookup.Missing("The wall macro selected in Walls is gone. Pick it again.")
    }

    // ---------- balances ----------

    private data class Balances(val gold: Long?, val elixir: Long?)

    /** What the last counter read produced, for the Walls status line. */
    var lastReading: WallCounters.Reading = WallCounters.Reading()
        private set

    private suspend fun readBalances(bitmap: Bitmap): Balances {
        val page = reader.page(bitmap)
        val sampler = BitmapIconColorSampler(bitmap)
        val reading = WallCounters.read(page.lines, page.width, page.height) { box ->
            sampler.classify(box)
        }
        lastReading = reading
        if (!reading.anything) {
            AppEventLog.log("walls: counters not found (${reading.describe()})")
        }
        return Balances(gold = reading.gold, elixir = reading.elixir)
    }

    private fun spend(before: Long?, after: Long?): Long? =
        if (before != null && after != null && after <= before) before - after else null

    // ---------- replay with checkpoints ----------

    private suspend fun replay(macro: Macro): String? {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val service = ClahAccessibilityService.instance ?: return "accessibility service is off"
        var lastTimestamp = 0L
        var sinceCheck = 0
        var lastCheckAt = SystemClock.elapsedRealtime()
        for (gesture in macro.gestures.sortedBy { it.timestampMs }) {
            if (stopRequested) return "stopped by you"
            val gap = MacroTimeline.gapMillis(
                (gesture.timestampMs - lastTimestamp).coerceAtLeast(0L),
                macro.speedMultiplier
            )
            if (gap > 0) delay(gap)
            sinceCheck++
            if (sinceCheck >= CHECKPOINT_EVERY_GESTURES &&
                SystemClock.elapsedRealtime() - lastCheckAt >= CHECKPOINT_MIN_MS
            ) {
                sinceCheck = 0
                lastCheckAt = SystemClock.elapsedRealtime()
                val reading = agent.observe() ?: return "no game frame"
                if (WallLogic.unsafeDuringRun(reading.observation.state)) {
                    return "the screen changed to ${name(reading.observation)}"
                }
            }
            if (!dispatch(service, gesture, macro.speedMultiplier, screenW, screenH)) {
                if (stopRequested) return "stopped by you"
                return "a gesture did not land"
            }
            lastTimestamp = gesture.timestampMs
        }
        return null
    }

    private fun name(observation: ScreenObservation): String =
        observation.state.name.lowercase(Locale.US)

    private suspend fun dispatch(
        service: AccessibilityService,
        gesture: RecordedGesture,
        speed: Float,
        screenW: Int,
        screenH: Int
    ): Boolean {
        val paths = MacroTimeline.playbackPaths(gesture, speed, screenW, screenH)
        if (paths.isEmpty()) return false
        val builder = GestureDescription.Builder()
        for (path in paths) {
            val line = Path()
            val first = path.points.first()
            line.moveTo(first.x, first.y)
            for (index in 1 until path.points.size) {
                line.lineTo(path.points[index].x, path.points[index].y)
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    line,
                    path.startOffsetMs,
                    path.durationMs.coerceAtLeast(1L)
                )
            )
        }
        val total = paths.maxOf { it.startOffsetMs + it.durationMs }
        return withTimeoutOrNull(total + DISPATCH_SLACK_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                val accepted = service.dispatchGesture(builder.build(), callback, null)
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        } ?: false
    }

    private suspend fun verifySafeAfterRun(): Boolean {
        var waited = 0L
        while (waited < AFTER_WAIT_MS) {
            val reading = agent.observe() ?: return false
            if (WallLogic.safeAfterRun(reading.observation.state)) return true
            if (reading.observation.state != GameState.LOADING) return false
            delay(POLL_MS)
            waited += POLL_MS
        }
        return false
    }

    // ---------- reporting ----------

    private fun log(
        macro: Macro,
        trigger: WallTrigger,
        gold: Long?,
        elixir: Long?,
        goldAfter: Long?,
        elixirAfter: Long?,
        status: String,
        detail: String
    ) {
        repository.recordRun(
            WallRunLog(
                at = System.currentTimeMillis(),
                macroName = macro.name,
                resource = trigger,
                goldBefore = gold,
                goldAfter = goldAfter,
                elixirBefore = elixir,
                elixirAfter = elixirAfter,
                status = status,
                detail = detail
            )
        )
    }

    private fun describe(
        gold: Long?,
        elixir: Long?,
        settings: WallSettings
    ): String = " — gold ${gold?.let { compact(it) } ?: "?"}, " +
        "elixir ${elixir?.let { compact(it) } ?: "?"}, " +
        "cost ${compact(settings.cost())}, reserve ${compact(settings.reserve())}"

    private fun WallTrigger.resourceName(): String = when (this) {
        WallTrigger.GOLD -> "gold"
        WallTrigger.ELIXIR -> "elixir"
        WallTrigger.NONE -> "no resource"
    }

    private fun compact(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> "${value / 1_000}K"
        else -> value.toString()
    }

    companion object {
        private const val POLL_MS = 700L
        private const val SETTLE_MS = 1_200L
        private const val HOME_WAIT_MS = 12_000L
        private const val AFTER_WAIT_MS = 8_000L
        private const val COOLDOWN_MS = 20_000L
        /** Ceiling for repeat upgrades in one visit; stops a runaway spend. */
        private const val MAX_REPEAT_UPGRADES = 5
        private const val CHECKPOINT_EVERY_GESTURES = 4
        private const val CHECKPOINT_MIN_MS = 1_200L
        private const val DISPATCH_SLACK_MS = 1_500L
    }
}
