package com.clahbot.results

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.clahbot.data.AppEventLog
import com.clahbot.data.AttackRepository
import com.clahbot.data.SettingsStore
import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.AttackRecordPolicy
import com.clahbot.data.models.AttackTrigger
import com.clahbot.ocr.ReaderStrategy
import com.clahbot.ocr.ResultsRead
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Watches for result screens from playback start (or from a manual watch) until
 * four minutes after playback ends.
 *
 * One detected screen becomes one episode. Frames are captured every 250 ms for
 * the first two seconds and every 500 ms afterwards, and the record is written
 * exactly once when the episode reports that the loot and bonus are settled (or
 * that the screen closed / the 12 s limit was reached). The overlay is never
 * hidden: on Android 14+ only the game window is captured; the display-capture
 * fallback masks the overlay regions instead.
 */
class ResultsCaptureCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val attackRepository: AttackRepository,
    private val settingsStore: SettingsStore,
    private val reader: AppResultsReader,
    private val gate: ResultCaptureGate,
    private val onStatus: (String) -> Unit,
    private val onEpisodeActive: (Boolean) -> Unit = {},
    private val onRecordSaved: (AttackRecord) -> Unit,
    private val onWatchEnded: () -> Unit
) {

    private class SessionState(val session: CaptureSession) {
        val baseline = CompletableDeferred<Boolean>()
        val manual = session.macroId == 0L

        @Volatile
        var baselineWasResults: Boolean = false

        @Volatile
        var playbackEndedAt: Long? = null

        @Volatile
        var interrupted: Boolean = false

        @Volatile
        var lastRecordStartedAt: Long? = null

        @Volatile
        var lastRecord: AttackRecord? = null

        @Volatile
        var records: Int = 0
    }

    private class Shot(
        val bitmap: Bitmap,
        val method: String,
        var score: Int = 0
    )

    private class FrameLog(
        val atMs: Long,
        val shot: ClahAccessibilityService.CaptureShot?,
        val read: AppResultsReader.EpisodeRead?,
        val readMs: Long
    )

    @Volatile
    private var current: SessionState? = null

    private var baselineJob: Job? = null
    private var watchJob: Job? = null

    fun isActive(): Boolean = current != null

    fun begin(session: CaptureSession) {
        val previousId = current?.session?.id
        val replaced = current != null
        cancel()
        gate.beginSession()
        val state = SessionState(session)
        current = state
        event(
            "watch begin id=${session.id} manual=${state.manual} macroId=${session.macroId} " +
                "replaced=$replaced previous=${previousId ?: "-"}"
        )
        scope.launch(Dispatchers.Default) {
            runCatching { reader.warmUp() }
        }
        baselineJob = scope.launch(Dispatchers.Default) {
            val startedOnResults = runCatching {
                val shot = ClahAccessibilityService.captureShot() ?: return@runCatching false
                try {
                    val check = reader.checkResultsScreen(shot.bitmap)
                    if (!check.isResults) {
                        event("baseline check: ${check.reason ?: "no anchor"} method=${shot.method}")
                    }
                    check.isResults
                } finally {
                    shot.bitmap.recycle()
                }
            }.getOrDefault(false)
            state.baselineWasResults = startedOnResults
            state.baseline.complete(startedOnResults)
        }
        watchJob = scope.launch(Dispatchers.Default) {
            watch(state)
        }
    }

    fun finish(
        session: CaptureSession,
        playbackEndedAt: Long,
        interrupted: Boolean
    ) {
        val state = current ?: return
        if (state.session.id != session.id) return
        state.playbackEndedAt = playbackEndedAt
        state.interrupted = interrupted
        event("playback end id=${session.id} interrupted=$interrupted")
        if (watchJob?.isActive != true) {
            watchJob = scope.launch(Dispatchers.Default) {
                watch(state)
            }
        }
    }

    fun cancel() {
        val cancelledId = current?.session?.id
        val hadSession = current != null
        watchJob?.cancel()
        watchJob = null
        baselineJob?.cancel()
        baselineJob = null
        current = null
        onEpisodeActive(false)
        gate.releaseHold()
        if (hadSession) event("watch cancelled id=${cancelledId ?: "-"}")
    }

    private suspend fun watch(state: SessionState) {
        val startedOnResults = withTimeoutOrNull(5_000) { state.baseline.await() } ?: false
        event("baseline results screen=$startedOnResults")
        if (startedOnResults && !state.manual) {
            status("Already on a results screen; no new attack recorded.")
            event("watch aborted: already on results (macro)")
            clear(state)
            return
        }
        if (startedOnResults) {
            event("late manual arm: capturing the open screen if it is new")
            status("Capturing the open result if it is new.")
        }

        val tracker = CaptureCycleTracker()
        var poll = 0
        var shotFailures = 0
        var endReason = "loop exited"
        try {
            while (coroutineContext.isActive) {
                val endedAt = state.playbackEndedAt
                val deadline = when {
                    endedAt != null -> minOf(
                        endedAt + CAPTURE_WINDOW_MS,
                        state.session.startedAt + MAX_WATCH_MS
                    )
                    state.manual -> state.session.startedAt + MANUAL_MAX_WATCH_MS
                    else -> state.session.startedAt + MAX_WATCH_MS
                }
                if (System.currentTimeMillis() >= deadline) {
                    endReason = when {
                        endedAt != null -> "capture window expired"
                        state.manual -> "manual watch limit expired"
                        else -> "max watch expired"
                    }
                    break
                }
                if (!ClahAccessibilityService.isGameForeground()) {
                    gate.notePoll(System.currentTimeMillis(), false, "game not foreground")
                    event(
                        "poll skipped: game not foreground " +
                            "foreground=${ClahAccessibilityService.foregroundLabel()}"
                    )
                    delay(RETRY_DELAY_MS)
                    continue
                }

                val pollStartedAt = System.currentTimeMillis()
                val shotStart = System.nanoTime()
                val shot = ClahAccessibilityService.captureShot()
                val shotMs = (System.nanoTime() - shotStart) / 1_000_000L
                if (shot == null) {
                    shotFailures++
                    gate.notePoll(pollStartedAt, false, "screenshot failed")
                    event(
                        "poll #${++poll} t=${pollStartedAt - state.session.startedAt}ms " +
                            "screenshot failed after ${shotMs}ms streak=$shotFailures"
                    )
                    delay(RETRY_DELAY_MS)
                    continue
                }
                shotFailures = 0
                val bitmap = shot.bitmap
                val screenHash = runCatching { ScreenHash.fromBitmap(bitmap) }.getOrNull()

                // After a record, skip OCR while the same screen is still up.
                if (tracker.isRecordedScreen(screenHash)) {
                    tracker.onPoll(results = false, hash = screenHash)
                    gate.notePoll(pollStartedAt, false, "same recorded screen")
                    bitmap.recycle()
                    delay(SCREEN_CLEAR_POLL_MS)
                    continue
                }

                val detectStart = System.nanoTime()
                val check = reader.checkResultsScreen(bitmap)
                val detectMs = (System.nanoTime() - detectStart) / 1_000_000L
                if (!check.isResults) {
                    tracker.onPoll(results = false, hash = screenHash)
                    gate.notePoll(pollStartedAt, false, check.reason)
                    event(
                        "poll #${++poll} t=${pollStartedAt - state.session.startedAt}ms " +
                            "shot=${shotMs}ms detect=${detectMs}ms results=false " +
                            "hash=${screenHash ?: "-"} reason=${check.reason ?: "-"} " +
                            "method=${shot.method}"
                    )
                    bitmap.recycle()
                    delay(DETECT_INTERVAL_MS)
                    continue
                }

                val action = tracker.onPoll(results = true, hash = screenHash)
                event(
                    "poll #${++poll} t=${pollStartedAt - state.session.startedAt}ms " +
                        "shot=${shotMs}ms detect=${detectMs}ms results=true " +
                        "hash=${screenHash ?: "-"} action=$action records=${state.records} " +
                        "method=${shot.method}"
                )
                if (action != PollAction.CAPTURE) {
                    gate.notePoll(pollStartedAt, false, action.name.lowercase(Locale.US))
                    bitmap.recycle()
                    delay(DETECT_INTERVAL_MS)
                    continue
                }

                val duplicateHash = screenHash != null && attackRepository.hasRecentMatch(
                    screenHash = screenHash,
                    gold = null,
                    elixir = null,
                    darkElixir = null,
                    outcome = null,
                    since = pollStartedAt - DUPLICATE_WINDOW_MS
                )
                if (duplicateHash) {
                    event("screen hash already recorded; waiting for the next result")
                    gate.notePoll(pollStartedAt, false, "already recorded")
                    tracker.onRecorded(screenHash)
                    status("This result is already recorded; waiting for the next one.")
                    bitmap.recycle()
                    delay(DETECT_INTERVAL_MS)
                    continue
                }

                gate.requestHold()
                onEpisodeActive(true)
                gate.notePoll(pollStartedAt, true, null)
                event(
                    "results detected; episode start; frame=${bitmap.width}x${bitmap.height}; " +
                        "${gate.snapshot()}"
                )
                val recordStartedAt = state.lastRecordStartedAt ?: state.session.startedAt
                val recordId = nextRecordId(state, pollStartedAt)
                val saved = try {
                    runEpisode(
                        state = state,
                        detectionShot = shot,
                        detectionCheck = check,
                        screenHash = screenHash,
                        recordId = recordId,
                        recordStartedAt = recordStartedAt
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    warn("Results episode failed", throwable)
                    false
                } finally {
                    gate.releaseHold()
                    event("hold released; ${gate.snapshot()}")
                }
                tracker.onRecorded(screenHash)
                if (saved) {
                    state.records++
                    state.lastRecordStartedAt = System.currentTimeMillis()
                }
                // No capture and no OCR while the recorded screen is still up.
                delay(RECORD_COOLDOWN_MS)
                onEpisodeActive(false)
            }
            event(
                "watch ended reason=$endReason polls=$poll records=${state.records} " +
                    "last=${gate.snapshot()}"
            )
            if (state.records == 0) {
                status("No results screen detected; no attack recorded.")
            } else {
                status("Watching stopped; ${com.clahbot.util.Plural.of(state.records, "result")} saved.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            warn("Results capture failed", throwable)
            status("Results capture failed; no attack recorded.")
        } finally {
            gate.releaseHold()
            val wasCurrent = current === state
            if (wasCurrent) current = null
            event(
                "watch finally id=${state.session.id} polls=$poll records=${state.records} " +
                    "wasCurrent=$wasCurrent current=${current?.session?.id ?: "-"}"
            )
            event(if (state.manual) "manual watch ended" else "watch ended")
            withContext(Dispatchers.Main) { runCatching { onWatchEnded() } }
        }
    }

    private suspend fun runEpisode(
        state: SessionState,
        detectionShot: ClahAccessibilityService.CaptureShot,
        detectionCheck: AppResultsReader.ScreenCheck,
        screenHash: String?,
        recordId: Long,
        recordStartedAt: Long
    ): Boolean {
        val episodeStart = System.currentTimeMillis()
        val policy = EpisodePolicy(startMs = episodeStart)
        var best = Shot(detectionShot.bitmap, detectionShot.method)
        val frames = mutableListOf<FrameLog>()
        var decision: EpisodeDecision = EpisodeDecision.Continue
        var frameIndex = 0
        var earlySaved = false

        try {
            while (coroutineContext.isActive && decision == EpisodeDecision.Continue) {
                val frameStart = System.currentTimeMillis()
                val shotMsStart = System.nanoTime()
                val shot: ClahAccessibilityService.CaptureShot? = if (frameIndex == 0) {
                    detectionShot
                } else {
                    ClahAccessibilityService.captureShot()
                }
                val shotMs = (System.nanoTime() - shotMsStart) / 1_000_000L
                if (shot == null) {
                    val at = System.currentTimeMillis()
                    decision = policy.onMissing(at)
                    frames += FrameLog(at - episodeStart, null, null, 0L)
                    if (decision == EpisodeDecision.Continue) {
                        val spent = System.currentTimeMillis() - frameStart
                        delay((intervalFor(at - episodeStart) - spent).coerceAtLeast(0L))
                    }
                    frameIndex++
                    continue
                }

                val readStart = System.nanoTime()
                val read = reader.readEpisode(
                    shot.bitmap,
                    known = if (frameIndex == 0) detectionCheck else null
                )
                val readMs = (System.nanoTime() - readStart) / 1_000_000L
                frames += FrameLog(
                    System.currentTimeMillis() - episodeStart,
                    shot,
                    read,
                    readMs
                )
                val at = System.currentTimeMillis()
                val resultsScreen = read?.inspection != null
                val value = read?.read
                event(
                    "episode frame #$frameIndex t=${at - episodeStart}ms method=${shot.method} " +
                        "shot=${shotMs}ms read=${readMs}ms results=$resultsScreen " +
                        "gold=${presence(value?.gold)} elixir=${presence(value?.elixir)} " +
                        "dark=${presence(value?.darkElixir)} " +
                        "bonus=${presence(value?.bonusGold)}/${presence(value?.bonusElixir)}/" +
                        "${presence(value?.bonusDarkElixir)} panel=${value?.bonusPanel == true} " +
                        "bonusRows=${read?.inspection?.bonusRows?.size ?: 0}"
                )
                val frameScore = frameScore(read)
                if (frameScore >= best.score && shot.bitmap !== best.bitmap) {
                    if (best.bitmap !== detectionShot.bitmap) best.bitmap.recycle()
                    best = Shot(shot.bitmap, shot.method, frameScore)
                } else if (shot.bitmap !== best.bitmap && shot.bitmap !== detectionShot.bitmap) {
                    shot.bitmap.recycle()
                }

                decision = if (!resultsScreen) {
                    policy.onMissing(at)
                } else {
                    policy.onFrame(
                        EpisodeFrame(
                            atMs = at,
                            resultsScreen = true,
                            read = value,
                            bonusRowBoxes = read?.inspection?.bonusRows?.map { it.box }
                                ?: emptyList(),
                            bonusRowCount = read?.inspection?.bonusRows?.size ?: 0
                        )
                    )
                }
                frameIndex++
                if (!earlySaved && policy.stableJustArrived()) {
                    // The main loot is confirmed: persist it now so the
                    // dashboard and Stats tab show it within about a second,
                    // then keep watching and update the same record.
                    earlySaved = true
                    event("main loot stable at ${at - episodeStart}ms; saving now")
                    runCatching {
                        saveEpisode(
                            state = state,
                            episode = policy.finishNow(at),
                            best = best,
                            frames = frames.toList(),
                            screenHash = screenHash,
                            recordId = recordId,
                            recordStartedAt = recordStartedAt,
                            updateExisting = false
                        )
                    }.onFailure { warn("Early save failed", it) }
                }
                if (decision == EpisodeDecision.Continue) {
                    val elapsed = System.currentTimeMillis() - episodeStart
                    val spent = System.currentTimeMillis() - frameStart
                    delay((intervalFor(elapsed) - spent).coerceAtLeast(0L))
                }
            }

            if (decision is EpisodeDecision.Finish) {
                val episode = decision.result
                val firstComplete = frames.firstOrNull {
                    it.read?.read?.lootComplete == true
                }?.atMs
                event(
                    "episode end t=${episode.elapsedMs}ms firstComplete=${firstComplete ?: "-"}ms " +
                        "mainStable=${episode.mainStableAtMs ?: "-"}ms " +
                        "bonusSettled=${episode.bonusSettledAtMs ?: "-"}ms " +
                        "stable=${episode.mainStable} bonus=${episode.bonusState} " +
                        "resolved=${episode.bonusResolved} closed=${episode.closedEarly} " +
                        "reasons=${episode.reasons.joinToString("|")}"
                )
                return saveEpisode(
                    state = state,
                    episode = episode,
                    best = best,
                    frames = frames,
                    screenHash = screenHash,
                    recordId = recordId,
                    recordStartedAt = recordStartedAt,
                    updateExisting = earlySaved
                )
            }
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            warn("Episode read failed", throwable)
            val at = System.currentTimeMillis()
            val episode = policy.finishNow(at).copy(
                reasons = listOf("Couldn't read loot")
            )
            return saveEpisode(
                state = state,
                episode = episode,
                best = best,
                frames = frames,
                screenHash = screenHash,
                recordId = recordId,
                recordStartedAt = recordStartedAt
            )
        } finally {
            if (best.bitmap !== detectionShot.bitmap) best.bitmap.recycle()
            detectionShot.bitmap.recycle()
        }
    }

    private suspend fun saveEpisode(
        state: SessionState,
        episode: EpisodeResult,
        best: Shot,
        frames: List<FrameLog>,
        screenHash: String?,
        recordId: Long,
        recordStartedAt: Long,
        updateExisting: Boolean = false
    ): Boolean {
        val session = state.session.copy(id = recordId)
        val playbackEndedAt = state.playbackEndedAt ?: System.currentTimeMillis()
        val outcome = episode.outcome?.name?.lowercase(Locale.US) ?: "unknown"
        val already = !updateExisting && attackRepository.hasRecentMatch(
            screenHash = screenHash,
            gold = episode.gold,
            elixir = episode.elixir,
            darkElixir = episode.darkElixir,
            outcome = episode.outcome?.name?.lowercase(Locale.US),
            since = System.currentTimeMillis() - DUPLICATE_WINDOW_MS
        )
        if (already) {
            event("episode matches an existing record; not adding a duplicate")
            status("This result is already recorded; waiting for the next one.")
            return false
        }

        val saved = if (settingsStore.settings.value.saveResultsScreenshots) {
            withContext(Dispatchers.IO) {
                runCatching {
                    ResultsScreenshotStore(context).saveEpisode(
                        bitmap = best.bitmap,
                        session = session,
                        episode = episode,
                        frames = frames.map { frame ->
                            ResultsScreenshotStore.FrameEvidence(
                                atMs = frame.atMs,
                                method = frame.shot?.method,
                                resultsScreen = frame.read?.inspection != null,
                                read = frame.read?.read,
                                readMs = frame.readMs
                            )
                        }
                    )
                }.getOrElse { throwable ->
                    warn("Could not save episode capture", throwable)
                    SavedCapture(null, null, null)
                }
            }
        } else {
            SavedCapture(null, null, null)
        }
        event(
            "episode saved method=${best.method} png=${saved.png?.let { File(it).name } ?: "-"} " +
                "json=${saved.json?.let { File(it).name } ?: "-"}"
        )

        if (!ResultsCapturePolicy.mayAutoRecord(
                detectedResults = true,
                startedOnResults = state.baselineWasResults,
                manual = state.manual
            ) ||
            !AttackRecordPolicy.mayCreateRecord(AttackTrigger.DETECTED_RESULTS_SCREEN)
        ) {
            event("record blocked by policy")
            return false
        }
        val record = ResultsToAttackRecord.fromEpisode(
            session = session,
            episode = episode,
            playbackEndedAt = playbackEndedAt,
            interrupted = state.interrupted,
            screenshotPath = saved.png,
            sidecarPath = saved.json,
            recordId = recordId,
            recordStartedAt = recordStartedAt,
            captureSource = AttackRecord.SOURCE_AUTO,
            screenHash = screenHash
        )
        val stored = if (updateExisting) {
            attackRepository.update(record)
        } else {
            attackRepository.add(record)
        }
        if (stored == null) {
            warn("Attack record was not ${if (updateExisting) "updated" else "added"}")
            status("Results screen read, but the attack record was not saved.")
            return false
        }
        state.lastRecord = stored
        event(
            "record ${if (updateExisting) "updated" else "saved"} id=${stored.id} " +
                "outcome=${stored.outcome} lootCaptured=${stored.hasLoot} " +
                "complete=${stored.complete} review=${stored.reviewReason ?: "-"} " +
                "screenshot=${stored.resultsScreenshot?.let { File(it).name } ?: "-"} " +
                "recordOutcome=$outcome"
        )
        status(
            when {
                stored.reviewReason != null ->
                    "Attack saved; needs review: ${stored.reviewReason}"
                stored.hasLoot -> "Attack saved: ${record.outcome ?: "result"} (loot captured)"
                else -> "Results screen captured; loot not captured"
            } + "; watching for the next"
        )
        withContext(Dispatchers.Main) { onRecordSaved(stored) }
        return true
    }

    private fun intervalFor(elapsedMs: Long): Long =
        if (elapsedMs < FAST_POLL_MS) FAST_POLL_INTERVAL_MS else SLOW_POLL_INTERVAL_MS

    private fun frameScore(read: AppResultsReader.EpisodeRead?): Int {
        val value = read?.read ?: return if (read?.inspection != null) 1 else 0
        var score = 1
        if (value.mainLootComplete) score += 4
        if (value.darkElixir != null) score += 1
        if (value.bonusGold != null &&
            value.bonusElixir != null &&
            value.bonusDarkElixir != null
        ) {
            score += 2
        }
        return score
    }

    private fun nextRecordId(state: SessionState, now: Long): Long {
        var id = now
        while (id <= state.session.id) id++
        return id
    }

    private fun presence(value: Long?): String = if (value == null) "missing" else "ok"

    private suspend fun status(message: String) {
        withContext(Dispatchers.Main) { onStatus(message) }
    }

    private fun event(message: String) {
        Log.i(TAG, message)
        AppEventLog.log(message)
    }

    private fun warn(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
        val detail = throwable?.message?.let { ": $it" } ?: ""
        AppEventLog.log("WARN $message$detail")
    }

    private fun clear(state: SessionState) {
        if (current === state) current = null
    }

    companion object {
        private const val TAG = "AutoClashResults"
        private const val CAPTURE_WINDOW_MS = 240_000L
        private const val MAX_WATCH_MS = 60 * 60_000L
        private const val MANUAL_MAX_WATCH_MS = 60 * 60_000L
        /**
         * Two captures of the same result screen always land inside one episode
         * plus the record cooldown, so this only has to cover a stray re-read.
         * It used to be 10 minutes, which meant a screenshot-hash collision with
         * an earlier attack silently discarded a legitimate result.
         */
        internal const val DUPLICATE_WINDOW_MS = 45_000L
        private const val RETRY_DELAY_MS = 1_000L
        private const val DETECT_INTERVAL_MS = 700L
        private const val SCREEN_CLEAR_POLL_MS = 500L
        private const val FAST_POLL_MS = 2_000L
        private const val FAST_POLL_INTERVAL_MS = 250L
        private const val SLOW_POLL_INTERVAL_MS = 500L
        private const val RECORD_COOLDOWN_MS = 1_500L
    }
}
