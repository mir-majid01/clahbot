package com.clahbot.recovery

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.clahbot.data.AppEventLog
import com.clahbot.data.BotState
import com.clahbot.data.RecoveryStripUi
import com.clahbot.data.ResultToastUi
import com.clahbot.execution.MacroPlayer
import com.clahbot.game.GameInput
import com.clahbot.game.GameScreenEngine
import com.clahbot.game.GameState
import com.clahbot.game.PopupKind
import com.clahbot.game.ScreenObservation
import com.clahbot.results.AppResultsReader
import com.clahbot.results.ScreenHash
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One observe-act helper shared by the watchdog and the wall upgrader: capture
 * a game frame, run full-page OCR and classify the screen.
 */
class RecoveryAgent(
    private val engine: GameScreenEngine,
    private val reader: AppResultsReader
) {

    data class Reading(val frame: ClahAccessibilityService.GameFrame, val observation: ScreenObservation)

    suspend fun observe(): Reading? {
        val frame = ClahAccessibilityService.captureGameFrame() ?: return null
        val page = runCatching { reader.page(frame.bitmap) }.getOrNull()
        val observation = engine.inspect(
            frame.bitmap,
            page,
            ClahAccessibilityService.currentForegroundPackage()
        )
        return Reading(frame, observation)
    }

    /**
     * Clears a popup with the data-driven rules and the never-press guard.
     * Returns true when the screen no longer classifies as the popup.
     */
    suspend fun clearPopup(
        first: Reading,
        label: String,
        tryCount: Int = RECOVERY_TRIES,
        retryMs: Long = RECOVERY_RETRY_MS
    ): Boolean {
        var reading = first
        for (tryIndex in 1..tryCount) {
            BotState.setRecoveryStrip(
                RecoveryStripUi(label, tryIndex, tryCount)
            )
            val action = nextAction(reading.observation)
            if (action != null) {
                AppEventLog.log("recovery $label try $tryIndex: ${action.label}")
                dispatch(action)
            } else {
                AppEventLog.log("recovery $label try $tryIndex: no safe target")
            }
            delay(retryMs)
            val next = observe() ?: continue
            reading = next
            val state = next.observation.state
            if (state != GameState.POPUP && state != GameState.NOT_CLASH) {
                BotState.setRecoveryStrip(null)
                return true
            }
        }
        BotState.setRecoveryStrip(null)
        return false
    }

    fun nextAction(observation: ScreenObservation): SafeActionFactory.RecoveryAction? {
        val rules = RecoveryRepositoryHolder.get().userRules() + BuiltInRecoveryRules.all
        for (rule in rules) {
            if (!rule.matches(observation) { hash ->
                    ScreenHash.distance(observation.hash, hash)
                }
            ) {
                continue
            }
            val attempt = SafeActionFactory.resolve(observation, rule)
            if (attempt.action != null) return attempt.action
            AppEventLog.log("recovery rule ${rule.id} refused: ${attempt.refusal}")
        }
        return SafeActionFactory.resolveGeneric(observation)
            .firstOrNull { it.action != null }?.action
    }

    suspend fun dispatch(action: SafeActionFactory.RecoveryAction) {
        val frame = ClahAccessibilityService.captureGameFrame() ?: return
        val target = action.target
        if (target == null) {
            when (action.type) {
                RecoveryActionType.PRESS_BACK -> GameInput.globalBack()
                RecoveryActionType.GO_HOME -> goHome()
                else -> Unit
            }
            return
        }
        GameInput.tapBox(target, frame)
    }

    /** Return Home on the results / searching screens by caption. */
    suspend fun goHome() {
        val reading = observe() ?: return
        val line = reading.observation.lines.firstOrNull { it.text.contains("returnhome") }
            ?: return
        val box = com.clahbot.game.ScreenBox(
            left = (line.left * reading.frame.bitmap.width).toInt(),
            top = (line.top * reading.frame.bitmap.height).toInt(),
            right = (line.right * reading.frame.bitmap.width).toInt(),
            bottom = (line.bottom * reading.frame.bitmap.height).toInt()
        ).expanded(
            reading.frame.bitmap.width / 48,
            reading.frame.bitmap.height / 30,
            reading.frame.bitmap.width,
            reading.frame.bitmap.height
        )
        GameInput.tapBox(box, reading.frame)
    }

    companion object {
        const val RECOVERY_TRIES = 3
        const val RECOVERY_RETRY_MS = 800L
    }
}

/** Lazy repository access without leaking a context into singletons. */
internal object RecoveryRepositoryHolder {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): RecoveryRepository {
        val context = appContext ?: error("RecoveryRepositoryHolder.init not called")
        return RecoveryRepository.get(context)
    }
}

/**
 * Guards every macro run: watches the game screen at 1 Hz, closes popups with
 * the never-press guard, survives connection loss, brings Clash back when
 * another window takes over, and stops safely when the limits are reached.
 */
class RecoveryWatchdog(
    private val scope: CoroutineScope,
    private val context: Context,
    engine: GameScreenEngine,
    reader: AppResultsReader,
    private val onPlaybackAborted: (reason: String) -> Unit = {}
) {

    private val agent = RecoveryAgent(engine, reader)
    private var job: Job? = null

    @Volatile
    private var player: MacroPlayer? = null

    @Volatile
    private var recoveriesThisLoop = 0

    @Volatile
    private var stoppedByUs = false

    // -------- state tracking --------
    private var lastState: GameState? = null
    private var stateSince = 0L
    private var popupSince = 0L
    private var unknownSince = 0L
    private var loadingSince = 0L
    private var lastHash: String? = null
    private var hashSince = 0L

    // -------- sequence learning --------
    private var currentLoop = mutableListOf<GameState>()
    private var pendingLoop: List<GameState>? = null
    private var cleanLoops = 0

    val learned: Boolean get() = cleanLoops >= CLEAN_LOOPS_REQUIRED

    fun start(player: MacroPlayer?) {
        this.player = player
        stoppedByUs = false
        recoveriesThisLoop = 0
        lastState = null
        popupSince = 0L
        unknownSince = 0L
        loadingSince = 0L
        lastHash = null
        job?.cancel()
        job = scope.launch {
            AppEventLog.log("recovery watchdog started")
            while (isActive && !stoppedByUs) {
                delay(POLL_MS)
                runCatching { tick() }.onFailure { error ->
                    AppEventLog.log("watchdog tick failed: ${error.message}")
                }
            }
            AppEventLog.log("recovery watchdog stopped")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player = null
        BotState.setRecoveryStrip(null)
    }

    private suspend fun tick() {
        if (screenUnavailable()) {
            if (player?.isRunning() == true) {
                hardStop("screen off or locked")
            }
            return
        }
        val reading = agent.observe() ?: return
        val obs = reading.observation
        val now = System.currentTimeMillis()
        if (obs.state != lastState) {
            noteTransition(lastState, obs.state)
            lastState = obs.state
            stateSince = now
            popupSince = 0L
            unknownSince = 0L
            loadingSince = 0L
            if (obs.hash != lastHash) {
                lastHash = obs.hash
                hashSince = now
            }
        } else {
            val dwell = now - stateSince
            when (obs.state) {
                GameState.POPUP -> if (popupSince == 0L) popupSince = now
                GameState.UNKNOWN -> if (unknownSince == 0L) unknownSince = now
                GameState.LOADING -> if (loadingSince == 0L) loadingSince = now
                else -> Unit
            }
        }
        if (obs.hash != lastHash) {
            lastHash = obs.hash
            hashSince = now
        }

        when (obs.state) {
            GameState.NOT_CLASH -> onNotClash()
            GameState.POPUP -> onPopup(reading, now)
            GameState.LOADING -> if (loadingSince > LOADING_GRACE_MS) {
                agent.clearPopup(reading, "stuck loading", tryCount = 2)
                loadingSince = System.currentTimeMillis()
            }
            GameState.UNKNOWN -> onUnknown(reading, now)
            else -> Unit
        }

        checkFrozen(reading, now)
    }

    private suspend fun onPopup(reading: RecoveryAgent.Reading, now: Long) {
        val since = if (popupSince == 0L) {
            popupSince = now
            now
        } else {
            popupSince
        }
        if (now - since < POPUP_GRACE_MS) return
        if (!spendBudget()) return
        recoveriesThisLoop++
        if (recoveriesThisLoop > MAX_RECOVERIES_PER_LOOP) {
            hardStop("more than $MAX_RECOVERIES_PER_LOOP recoveries in one loop")
            return
        }
        val recovered = agent.clearPopup(reading, "Closing a popup")
        if (recovered) {
            RecoveryRepositoryHolder.get().noteRecovered()
            player?.resume()
        } else {
            RecoveryRepositoryHolder.get().noteStopped()
            abortPlayback("recovery failed after ${RecoveryAgent.RECOVERY_TRIES} tries")
        }
        popupSince = 0L
    }

    private suspend fun onUnknown(reading: RecoveryAgent.Reading, now: Long) {
        val since = if (unknownSince == 0L) {
            unknownSince = now
            now
        } else {
            unknownSince
        }
        if (now - since < UNKNOWN_SAVE_MS) return
        unknownSince = now
        val label = learnedLoopContext()
        RecoveryRepositoryHolder.get().addUnknownScreen(reading.frame.bitmap, label)
        AppEventLog.log("watchdog saved unknown screen ($label)")
    }

    private fun checkFrozen(reading: RecoveryAgent.Reading, now: Long) {
        if (now - hashSince < FROZEN_MS) return
        val state = reading.observation.state
        val expectedWaiting = state == GameState.SEARCHING ||
            state == GameState.RESULTS ||
            state == GameState.LOADING
        if (expectedWaiting) {
            hashSince = now
            return
        }
        if (player?.isRunning() != true) {
            hashSince = now
            return
        }
        AppEventLog.log("watchdog: frozen screen in $state for ${FROZEN_MS}ms")
        scope.launch {
            val cleared = agent.clearPopup(reading, "Frozen screen", tryCount = 2)
            if (!cleared) {
                RecoveryRepositoryHolder.get().noteStopped()
                abortPlayback("screen frozen")
            } else {
                RecoveryRepositoryHolder.get().noteRecovered()
            }
            hashSince = System.currentTimeMillis()
        }
    }

    private suspend fun onNotClash() {
        val foreground = ClahAccessibilityService.currentForegroundPackage() ?: return
        if (foreground.startsWith(GameScreenEngine.CLASH_PACKAGE_PREFIX)) return
        AppEventLog.log("watchdog: not clash ($foreground)")
        GameInput.globalBack()
        delay(NOT_CLASH_BACK_WAIT_MS)
        val again = ClahAccessibilityService.currentForegroundPackage()
        if (again == null || !again.startsWith(GameScreenEngine.CLASH_PACKAGE_PREFIX)) {
            val intent = clashLaunchIntent()
            if (intent != null) {
                runCatching { context.startActivity(intent) }
            } else {
                AppEventLog.log("watchdog: no Clash launch intent")
            }
        }
        var waited = 0L
        while (waited < NOT_CLASH_RECOVER_MS) {
            delay(POLL_MS)
            waited += POLL_MS
            if (ClahAccessibilityService.isGameForeground()) return
            if (screenUnavailable()) {
                hardStop("screen off or locked")
                return
            }
        }
    }

    // -------- bookkeeping --------

    private fun noteTransition(from: GameState?, to: GameState) {
        if (to == GameState.HOME || to == GameState.ATTACK_MENU) {
            if (currentLoop.contains(GameState.RESULTS)) {
                val completed = currentLoop.toList() + to
                val previous = pendingLoop
                if (previous == completed) {
                    cleanLoops++
                } else {
                    pendingLoop = completed
                    cleanLoops = 1
                }
                if (cleanLoops >= CLEAN_LOOPS_REQUIRED) {
                    AppEventLog.log(
                        "watchdog learned loop sequence: " +
                            completed.joinToString("->") { it.name }
                    )
                }
            }
            currentLoop.clear()
        }
        currentLoop.add(to)
    }

    private fun learnedLoopContext(): String {
        val loop = BotState.playbackRunLabel.value
        return if (loop != null) "during $loop" else "during playback"
    }

    private fun spendBudget(): Boolean {
        val repository = RecoveryRepositoryHolder.get()
        repository.noteAttempt()
        if (repository.attemptsInLastHour() > HOURLY_LIMIT) {
            hardStop("recovery limit: more than $HOURLY_LIMIT per hour")
            return false
        }
        return true
    }

    private fun abortPlayback(reason: String) {
        AppEventLog.log("watchdog abort: $reason")
        BotState.setRecoveryStrip(null)
        BotState.setResultToast(
            ResultToastUi.Kind.WARNING,
            "Stopped: $reason"
        )
        player?.stop()
        onPlaybackAborted(reason)
    }

    private fun hardStop(reason: String) {
        if (stoppedByUs) return
        stoppedByUs = true
        AppEventLog.log("watchdog hard stop: $reason")
        RecoveryRepositoryHolder.get().noteStopped()
        scope.launch {
            runCatching {
                ClahAccessibilityService.captureGameFrame()?.let { frame ->
                    RecoveryRepositoryHolder.get()
                        .addUnknownScreen(frame.bitmap, "Stopped — $reason")
                }
            }
        }
        BotState.setRecoveryStrip(null)
        BotState.setResultToast(ResultToastUi.Kind.WARNING, "Stopped: $reason")
        player?.stop()
        onPlaybackAborted(reason)
        stop()
    }

    private fun clashLaunchIntent(): Intent? {
        for (pkg in CLASH_PACKAGES) {
            val intent = runCatching {
                context.packageManager.getLaunchIntentForPackage(pkg)
            }.getOrNull()
            if (intent != null) {
                return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return null
    }

    private fun screenUnavailable(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (power != null && !power.isInteractive) return true
        if (keyguard != null && keyguard.isKeyguardLocked) return true
        return false
    }

    companion object {
        private const val POLL_MS = 1_000L
        private const val POPUP_GRACE_MS = 6_000L
        private const val LOADING_GRACE_MS = 25_000L
        private const val UNKNOWN_SAVE_MS = 15_000L
        private const val FROZEN_MS = 20_000L
        private const val NOT_CLASH_BACK_WAIT_MS = 1_500L
        private const val NOT_CLASH_RECOVER_MS = 20_000L
        private const val MAX_RECOVERIES_PER_LOOP = 3
        private const val HOURLY_LIMIT = 8
        private const val CLEAN_LOOPS_REQUIRED = 3

        private val CLASH_PACKAGES = listOf(
            "com.supercell.clashofclans",
            "com.supercell.clashofclans.b"
        )
    }
}
