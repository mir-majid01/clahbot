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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Watches for the result screen from playback start (or from a manual watch)
 * until four minutes after playback ends. The watch has to run during playback
 * because a recorded macro normally taps Return Home while the result screen is
 * open; waiting for the playback to end would miss the screen entirely. The
 * controller overlay is hidden so screenshots contain only the game. A
 * playback ending never writes a record; only a detected result screen can. A
 * result screen that was already open before the watch started is stale and is
 * ignored.
 *
 * Every poll and every reader decision is written to the rolling app event log.
 */
class ResultsCaptureCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val attackRepository: AttackRepository,
    private val settingsStore: SettingsStore,
    private val reader: AppResultsReader,
    private val gate: ResultCaptureGate,
    private val hideOverlay: () -> Unit,
    private val showOverlay: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRecordSaved: () -> Unit,
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
        var lastRefineAt: Long = 0L

        @Volatile
        var refineAttempts: Int = 0

        @Volatile
        var records: Int = 0
    }

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
                "replaced=${replaced} previous=${previousId ?: "-"}"
        )
        baselineJob = scope.launch(Dispatchers.Default) {
            val startedOnResults = runCatching {
                val bitmap = ClahAccessibilityService.captureScreenshot()
                    ?: return@runCatching false
                try {
                    val check = reader.checkResultsScreen(bitmap)
                    if (!check.isResults) {
                        event("baseline check: ${check.reason ?: "no anchor"}")
                    }
                    check.isResults
                } finally {
                    bitmap.recycle()
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
        gate.releaseHold()
        if (hadSession) event("watch cancelled id=${cancelledId ?: "-"}")
        scope.launch(Dispatchers.Main) {
            runCatching { showOverlay() }
        }
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
                val detectionBitmap = ClahAccessibilityService.captureScreenshot()
                val shotMs = (System.nanoTime() - shotStart) / 1_000_000L
                if (detectionBitmap == null) {
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

                val detectStart = System.nanoTime()
                val check = reader.checkResultsScreen(detectionBitmap)
                val detectMs = (System.nanoTime() - detectStart) / 1_000_000L
                val screenHash = runCatching { ScreenHash.fromBitmap(detectionBitmap) }.getOrNull()
                if (!check.isResults) {
                    tracker.onPoll(results = false, hash = screenHash)
                    gate.notePoll(pollStartedAt, false, check.reason)
                    event(
                        "poll #${++poll} t=${pollStartedAt - state.session.startedAt}ms " +
                            "shot=${shotMs}ms detect=${detectMs}ms results=false " +
                            "hash=${screenHash ?: "-"} reason=${check.reason ?: "-"}"
                    )
                    detectionBitmap.recycle()
                    continue
                }

                val action = tracker.onPoll(results = true, hash = screenHash)
                event(
                    "poll #${++poll} t=${pollStartedAt - state.session.startedAt}ms " +
                        "shot=${shotMs}ms detect=${detectMs}ms results=true " +
                        "hash=${screenHash ?: "-"} action=$action records=${state.records}"
                )
                if (action != PollAction.CAPTURE) {
                    if (action == PollAction.WAIT_FOR_CLEAR ||
                        action == PollAction.ALREADY_RECORDED
                    ) {
                        refineEpisodeIfNeeded(state)
                    }
                    gate.notePoll(pollStartedAt, false, action.name.lowercase(Locale.US))
                    detectionBitmap.recycle()
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
                    detectionBitmap.recycle()
                    continue
                }

                gate.requestHold()
                gate.notePoll(pollStartedAt, true, null)
                event(
                    "results detected; hold requested; frame=${detectionBitmap.width}x" +
                        "${detectionBitmap.height}; ${gate.snapshot()}"
                )
                var cleanBitmap: Bitmap? = null
                var settleBitmap: Bitmap? = null
                var cleanCheck: AppResultsReader.ScreenCheck? = null
                try {
                    withContext(Dispatchers.Main) { hideOverlay() }
                    delay(CLEAN_CAPTURE_DELAY_MS)
                    val cleanShotStart = System.nanoTime()
                    cleanBitmap = ClahAccessibilityService.captureScreenshot()
                    val cleanShotMs = (System.nanoTime() - cleanShotStart) / 1_000_000L
                    val clean = cleanBitmap
                    event(
                        "clean capture shot=${cleanShotMs}ms " +
                            "bitmap=${clean?.let { "${it.width}x${it.height}" } ?: "null"}"
                    )
                    var readResult: AppResultsReader.ReadResult? = null
                    var readSource = "clean"
                    if (clean != null) {
                        val cleanReadStart = System.nanoTime()
                        readResult = withTimeoutOrNull(READ_TIMEOUT_MS) {
                            reader.read(clean)
                        }
                        val cleanReadMs = (System.nanoTime() - cleanReadStart) / 1_000_000L
                        if (readResult != null) {
                            event("clean read ok after ${cleanReadMs}ms")
                        } else {
                            cleanCheck = withTimeoutOrNull(5_000) {
                                reader.checkResultsScreen(clean)
                            }
                            event(
                                "clean frame read returned no result after ${cleanReadMs}ms; " +
                                    "check=${cleanCheck?.isResults} " +
                                    "reason=${cleanCheck?.reason ?: "timeout"}; " +
                                    "trying detection frame"
                            )
                        }
                    } else {
                        event("clean capture failed; trying detection frame")
                    }
                    if (readResult == null) {
                        readResult = withTimeoutOrNull(READ_TIMEOUT_MS) {
                            reader.read(detectionBitmap)
                        }
                        readSource = "detection"
                        if (readResult != null) {
                            event("detection fallback read ok")
                        }
                    }

                    // Never save the first frame of a loot count-up. Wait for
                    // two consecutive complete reads with the same outcome and
                    // all three main values; partial/animated frames are ignored.
                    val gate = StableReadGate()
                    var stableRead: AppResultsReader.ReadResult? = null
                    if (readResult != null && gate.offer(readResult.read) != null) {
                        stableRead = readResult
                    }
                    var stableAttempts = 0
                    while (stableRead == null && stableAttempts < STABLE_READ_ATTEMPTS) {
                        stableAttempts++
                        delay(STABLE_READ_DELAY_MS)
                        val shot = ClahAccessibilityService.captureScreenshot()
                        if (shot == null) continue
                        val next = withTimeoutOrNull(READ_TIMEOUT_MS) { reader.read(shot) }
                        if (next == null) {
                            shot.recycle()
                            continue
                        }
                        if (gate.offer(next.read) != null) {
                            stableRead = next
                            settleBitmap = shot
                            readSource = "stable"
                            event("read stabilized after $stableAttempts extra frame(s)")
                            break
                        }
                        shot.recycle()
                    }
                    if (stableRead != null) {
                        readResult = stableRead
                    } else {
                        if (readResult != null) {
                            event("loot did not stabilize; saving without values")
                        }
                        readResult = null
                    }

                    if (readResult != null) {
                        val read = readResult.read
                        val outcome = read.outcome.name.lowercase(Locale.US)
                        val already = attackRepository.hasRecentMatch(
                            screenHash = screenHash,
                            gold = read.gold,
                            elixir = read.elixir,
                            darkElixir = read.darkElixir,
                            outcome = outcome,
                            since = System.currentTimeMillis() - DUPLICATE_WINDOW_MS
                        )
                        if (already) {
                            event("read matches an existing record; not adding a duplicate")
                            tracker.onRecorded(screenHash)
                            status("This result is already recorded; waiting for the next one.")
                        } else {
                            val now = System.currentTimeMillis()
                            val recordId = nextRecordId(state, now)
                            val recordStartedAt = state.lastRecordStartedAt ?: state.session.startedAt
                            val added = saveAndRecord(
                                state = state,
                                bitmap = settleBitmap
                                    ?: if (readSource == "clean") clean ?: detectionBitmap else detectionBitmap,
                                result = readResult,
                                source = readSource,
                                recordId = recordId,
                                recordStartedAt = recordStartedAt,
                                screenHash = screenHash
                            )
                            tracker.onRecorded(screenHash)
                            if (added) {
                                state.lastRecordStartedAt = now
                                state.records++
                            }
                        }
                    } else {
                        val now = System.currentTimeMillis()
                        val already = screenHash != null && attackRepository.hasRecentMatch(
                            screenHash = screenHash,
                            gold = null,
                            elixir = null,
                            darkElixir = null,
                            outcome = null,
                            since = now - DUPLICATE_WINDOW_MS
                        )
                        if (already) {
                            event("unreadable screen hash already recorded; not adding a duplicate")
                            tracker.onRecorded(screenHash)
                        } else {
                            val recordId = nextRecordId(state, now)
                            val recordStartedAt = state.lastRecordStartedAt ?: state.session.startedAt
                            saveUnreadableAndRecord(
                                state = state,
                                bitmap = cleanBitmap ?: detectionBitmap,
                                recordId = recordId,
                                recordStartedAt = recordStartedAt,
                                screenHash = screenHash,
                                reason = "both frames failed to read; " +
                                    "cleanCheck=${cleanCheck?.isResults} " +
                                    "cleanReason=${cleanCheck?.reason ?: "-"}"
                            )
                            tracker.onRecorded(screenHash)
                            state.lastRecordStartedAt = now
                            state.records++
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    warn("Results OCR failed", throwable)
                } finally {
                    gate.releaseHold()
                    event("hold released; ${gate.snapshot()}")
                    cleanBitmap?.recycle()
                    settleBitmap?.recycle()
                    detectionBitmap.recycle()
                    withContext(Dispatchers.Main) { runCatching { showOverlay() } }
                }
                delay(RETRY_DELAY_MS)
            }
            event(
                "watch ended reason=$endReason polls=$poll records=${state.records} " +
                    "last=${gate.snapshot()}"
            )
            if (state.records == 0) {
                status("No results screen detected; no attack recorded.")
            } else {
                status("Watching stopped; ${state.records} result(s) saved.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            warn("Results capture failed", throwable)
            status("Results capture failed; no attack recorded.")
        } finally {
            gate.releaseHold()
            withContext(Dispatchers.Main) { runCatching { showOverlay() } }
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

    /**
     * While the same result screen is still open, fill only the fields the
     * saved record is missing. A second record is never created here.
     */
    private suspend fun refineEpisodeIfNeeded(state: SessionState) {
        val record = state.lastRecord ?: return
        if (record.complete && record.bonusState != AttackRecord.BONUS_NOT_CAPTURED) return
        val now = System.currentTimeMillis()
        if (state.refineAttempts >= REFINE_ATTEMPTS) return
        if (now - state.lastRefineAt < REFINE_INTERVAL_MS) return
        state.lastRefineAt = now
        state.refineAttempts++
        var bitmap: Bitmap? = null
        try {
            withContext(Dispatchers.Main) { hideOverlay() }
            val shot = ClahAccessibilityService.captureScreenshot() ?: return
            bitmap = shot
            val result = withTimeoutOrNull(READ_TIMEOUT_MS) {
                reader.read(shot, ReaderStrategy.ALL)
            } ?: return
            val filled = EpisodeFillPolicy.fill(record, result.read)
            if (!filled.changed) return
            val saved = attackRepository.update(filled.record) ?: return
            state.lastRecord = saved
            event(
                "episode refine id=${saved.id} complete=${saved.complete} " +
                    "review=${saved.reviewReason ?: "-"}"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            warn("Episode refine failed", throwable)
        } finally {
            bitmap?.recycle()
            withContext(Dispatchers.Main) { runCatching { showOverlay() } }
        }
    }

    private fun nextRecordId(state: SessionState, now: Long): Long {
        var id = now
        while (id <= state.session.id) id++
        return id
    }

    private suspend fun saveAndRecord(
        state: SessionState,
        bitmap: Bitmap,
        result: AppResultsReader.ReadResult,
        source: String,
        recordId: Long,
        recordStartedAt: Long,
        screenHash: String?
    ): Boolean {
        val session = state.session.copy(id = recordId)
        val playbackEndedAt = state.playbackEndedAt ?: System.currentTimeMillis()
        val interrupted = state.interrupted
        val read = result.read
        event(
            "read ($source) outcome=${read.outcome} " +
                "gold=${presence(read.gold)} elixir=${presence(read.elixir)} " +
                "dark=${presence(read.darkElixir)} " +
                "bonus=${presence(read.bonusGold)}/${presence(read.bonusElixir)}/" +
                "${presence(read.bonusDarkElixir)} " +
                "confidence=${String.format(Locale.US, "%.2f", read.confidence)} " +
                "needsReview=${read.needsReview} issues=${read.issues.take(3).joinToString("; ")}"
        )
        val saved = if (settingsStore.settings.value.saveResultsScreenshots) {
            withContext(Dispatchers.IO) {
                runCatching {
                    ResultsScreenshotStore(context).save(bitmap, session, result)
                }.getOrElse { throwable ->
                    warn("Could not save result capture", throwable)
                    SavedCapture(null, null, null)
                }
            }
        } else {
            SavedCapture(null, null, null)
        }
        event(
            "saved source=$source png=${saved.png?.let { File(it).name } ?: "-"} " +
                "json=${saved.json?.let { File(it).name } ?: "-"} " +
                "txt=${saved.txt?.let { File(it).name } ?: "-"}"
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
        val record = ResultsToAttackRecord.build(
            session = session,
            read = read,
            playbackEndedAt = playbackEndedAt,
            interrupted = interrupted,
            screenshotPath = saved.png,
            sidecarPath = saved.json,
            recordId = recordId,
            recordStartedAt = recordStartedAt,
            captureSource = AttackRecord.SOURCE_AUTO,
            screenHash = screenHash
        )
        val added = attackRepository.add(record)
        if (added == null) {
            warn("Attack record was not added (duplicate or invalid)")
            status("Results screen read, but the attack record was not saved.")
            return false
        }
        state.lastRecord = added
        state.refineAttempts = 0
        state.lastRefineAt = 0L
        event(
            "record saved id=${added.id} outcome=${added.outcome} " +
                "lootCaptured=${added.hasLoot} screenshot=${added.resultsScreenshot?.let { File(it).name } ?: "-"}"
        )
        status(
            if (record.hasLoot) {
                "Attack saved: ${record.outcome ?: "result"} (loot captured); watching for the next"
            } else {
                "Results screen captured; loot not captured; watching for the next"
            }
        )
        withContext(Dispatchers.Main) { onRecordSaved() }
        return true
    }

    private suspend fun saveUnreadableAndRecord(
        state: SessionState,
        bitmap: Bitmap,
        recordId: Long,
        recordStartedAt: Long,
        screenHash: String?,
        reason: String
    ) {
        val session = state.session.copy(id = recordId)
        val playbackEndedAt = state.playbackEndedAt ?: System.currentTimeMillis()
        val saved = if (settingsStore.settings.value.saveResultsScreenshots) {
            withContext(Dispatchers.IO) {
                runCatching {
                    ResultsScreenshotStore(context).saveUnreadable(bitmap, session, reason)
                }.getOrElse { throwable ->
                    warn("Could not save unreadable result capture", throwable)
                    SavedCapture(null, null, null)
                }
            }
        } else {
            SavedCapture(null, null, null)
        }
        event(
            "saved unreadable png=${saved.png?.let { File(it).name } ?: "-"} " +
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
            return
        }
        val record = ResultsToAttackRecord.buildUnreadable(
            session = session,
            playbackEndedAt = playbackEndedAt,
            interrupted = state.interrupted,
            screenshotPath = saved.png,
            sidecarPath = saved.json,
            recordId = recordId,
            recordStartedAt = recordStartedAt,
            screenHash = screenHash
        )
        val added = attackRepository.add(record)
        if (added == null) {
            warn("Unreadable attack record was not added")
            return
        }
        event("record saved id=${added.id} outcome=unknown lootCaptured=false")
        status("Results screen captured; loot not captured; watching for the next")
        withContext(Dispatchers.Main) { onRecordSaved() }
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
        private const val DUPLICATE_WINDOW_MS = 10 * 60_000L
        private const val RETRY_DELAY_MS = 1_000L
        private const val READ_TIMEOUT_MS = 25_000L
        private const val CLEAN_CAPTURE_DELAY_MS = 600L
        private const val STABLE_READ_ATTEMPTS = 3
        private const val STABLE_READ_DELAY_MS = 800L
        private const val REFINE_ATTEMPTS = 3
        private const val REFINE_INTERVAL_MS = 1_500L
    }
}
