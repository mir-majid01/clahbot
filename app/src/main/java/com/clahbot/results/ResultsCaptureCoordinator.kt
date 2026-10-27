package com.clahbot.results

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.clahbot.data.AppEventLog
import com.clahbot.data.AttackRepository
import com.clahbot.data.SettingsStore
import com.clahbot.data.models.AttackRecordPolicy
import com.clahbot.data.models.AttackTrigger
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
        var playbackEndedAt: Long? = null

        @Volatile
        var interrupted: Boolean = false
    }

    @Volatile
    private var current: SessionState? = null

    private var baselineJob: Job? = null
    private var watchJob: Job? = null

    fun isActive(): Boolean = current != null

    fun begin(session: CaptureSession) {
        cancel()
        gate.beginSession()
        val state = SessionState(session)
        current = state
        event("watch begin id=${session.id} manual=${state.manual} macroId=${session.macroId}")
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
        val hadSession = current != null
        watchJob?.cancel()
        watchJob = null
        baselineJob?.cancel()
        baselineJob = null
        current = null
        gate.releaseHold()
        if (hadSession) event("watch cancelled")
        scope.launch(Dispatchers.Main) {
            runCatching { showOverlay() }
        }
    }

    private suspend fun watch(state: SessionState) {
        val startedOnResults = withTimeoutOrNull(5_000) { state.baseline.await() } ?: false
        event("baseline results screen=$startedOnResults")
        if (startedOnResults) {
            status("Already on a results screen; no new attack recorded.")
            event("watch aborted: already on results")
            clear(state)
            return
        }

        var poll = 0
        try {
            while (coroutineContext.isActive) {
                val endedAt = state.playbackEndedAt
                val deadline = if (endedAt != null) {
                    minOf(
                        endedAt + CAPTURE_WINDOW_MS,
                        state.session.startedAt + MAX_WATCH_MS
                    )
                } else {
                    state.session.startedAt + MAX_WATCH_MS
                }
                if (System.currentTimeMillis() >= deadline) break
                if (!ClahAccessibilityService.isGameForeground()) {
                    gate.notePoll(System.currentTimeMillis(), false, "game not foreground")
                    event("poll skipped: game not foreground")
                    delay(RETRY_DELAY_MS)
                    continue
                }

                val pollStartedAt = System.currentTimeMillis()
                val shotStart = System.nanoTime()
                val detectionBitmap = ClahAccessibilityService.captureScreenshot()
                val shotMs = (System.nanoTime() - shotStart) / 1_000_000L
                if (detectionBitmap == null) {
                    gate.notePoll(pollStartedAt, false, "screenshot failed")
                    event(
                        "poll #${++poll} t=${pollStartedAt - state.session.startedAt}ms " +
                            "screenshot failed after ${shotMs}ms"
                    )
                    delay(RETRY_DELAY_MS)
                    continue
                }

                val detectStart = System.nanoTime()
                val check = reader.checkResultsScreen(detectionBitmap)
                val detectMs = (System.nanoTime() - detectStart) / 1_000_000L
                gate.notePoll(pollStartedAt, check.isResults, check.reason)
                event(
                    "poll #${++poll} t=${pollStartedAt - state.session.startedAt}ms " +
                        "shot=${shotMs}ms detect=${detectMs}ms results=${check.isResults} " +
                        "reason=${check.reason ?: "-"}"
                )
                if (!check.isResults) {
                    detectionBitmap.recycle()
                    // No retry delay: the result screen can be dismissed within
                    // a second by the macro's Return Home gesture.
                    continue
                }

                gate.requestHold()
                event("results detected; hold requested; ${gate.snapshot()}")
                var cleanBitmap: Bitmap? = null
                try {
                    // Hide the bubble only for the clean capture. The results
                    // screen can stay open for minutes, so keeping the overlay
                    // hidden for the whole watch would be wrong.
                    withContext(Dispatchers.Main) { hideOverlay() }
                    delay(CLEAN_CAPTURE_DELAY_MS)
                    cleanBitmap = ClahAccessibilityService.captureScreenshot()
                    val clean = cleanBitmap
                    if (clean != null) {
                        val cleanResult = withTimeoutOrNull(READ_TIMEOUT_MS) {
                            reader.read(clean)
                        }
                        if (cleanResult != null) {
                            saveAndRecord(state, clean, cleanResult, "clean")
                            clear(state)
                            return
                        }
                        event("clean frame read returned no result; trying detection frame")
                    } else {
                        event("clean capture failed; trying detection frame")
                    }
                    // The clean frame failed; the detection frame is still a
                    // valid read (the bubble may be in a corner).
                    val fallback = withTimeoutOrNull(READ_TIMEOUT_MS) {
                        reader.read(detectionBitmap)
                    }
                    if (fallback != null) {
                        saveAndRecord(state, detectionBitmap, fallback, "detection")
                        clear(state)
                        return
                    }
                    event("both frames failed to read")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    warn("Results OCR failed", throwable)
                } finally {
                    gate.releaseHold()
                    event("hold released; ${gate.snapshot()}")
                    cleanBitmap?.recycle()
                    detectionBitmap.recycle()
                    withContext(Dispatchers.Main) { runCatching { showOverlay() } }
                }
                delay(RETRY_DELAY_MS)
            }
            event("watch ended without a capture")
            status("No results screen detected; no attack recorded.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            warn("Results capture failed", throwable)
            status("Results capture failed; no attack recorded.")
        } finally {
            gate.releaseHold()
            withContext(Dispatchers.Main) { runCatching { showOverlay() } }
            if (current === state) current = null
            if (state.manual) {
                event("manual watch ended")
                withContext(Dispatchers.Main) { runCatching { onWatchEnded() } }
            }
        }
    }

    private suspend fun saveAndRecord(
        state: SessionState,
        bitmap: Bitmap,
        result: AppResultsReader.ReadResult,
        source: String
    ) {
        val session = state.session
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
                startedOnResults = false
            ) ||
            !AttackRecordPolicy.mayCreateRecord(AttackTrigger.DETECTED_RESULTS_SCREEN)
        ) {
            event("record blocked by policy")
            return
        }
        val record = ResultsToAttackRecord.build(
            session = session,
            read = read,
            playbackEndedAt = playbackEndedAt,
            interrupted = interrupted,
            screenshotPath = saved.png,
            sidecarPath = saved.json
        )
        val added = attackRepository.add(record)
        if (added == null) {
            warn("Attack record was not added (duplicate or invalid)")
            status("Results screen read, but the attack record was not saved.")
            return
        }
        event(
            "record saved id=${added.id} outcome=${added.outcome} " +
                "lootCaptured=${added.hasLoot} screenshot=${added.resultsScreenshot?.let { File(it).name } ?: "-"}"
        )
        status(
            if (record.hasLoot) {
                "Attack saved: ${record.outcome ?: "result"} (loot captured)"
            } else {
                "Results screen captured; loot not captured"
            }
        )
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
        private const val MAX_WATCH_MS = 15 * 60_000L
        private const val RETRY_DELAY_MS = 1_000L
        private const val READ_TIMEOUT_MS = 25_000L
        private const val CLEAN_CAPTURE_DELAY_MS = 600L
    }
}
