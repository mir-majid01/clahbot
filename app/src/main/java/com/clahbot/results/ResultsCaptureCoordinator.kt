package com.clahbot.results

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
import kotlin.coroutines.coroutineContext

/**
 * Watches for the result screen from playback start until four minutes after
 * playback ends. The watch has to run during playback because a recorded macro
 * normally taps Return Home while the result screen is open; waiting for the
 * playback to end would miss the screen entirely. The controller overlay is
 * hidden so screenshots contain only the game. A playback ending never writes
 * a record; only a detected result screen can. A result screen that was
 * already open before playback started is stale and is ignored.
 */
class ResultsCaptureCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val attackRepository: AttackRepository,
    private val settingsStore: SettingsStore,
    private val reader: AppResultsReader,
    private val hideOverlay: () -> Unit,
    private val showOverlay: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRecordSaved: () -> Unit
) {

    private class SessionState(val session: CaptureSession) {
        val baseline = CompletableDeferred<Boolean>()

        @Volatile
        var playbackEndedAt: Long? = null

        @Volatile
        var interrupted: Boolean = false
    }

    @Volatile
    private var current: SessionState? = null

    private var baselineJob: Job? = null
    private var watchJob: Job? = null

    fun begin(session: CaptureSession) {
        cancel()
        val state = SessionState(session)
        current = state
        baselineJob = scope.launch(Dispatchers.Default) {
            val startedOnResults = runCatching {
                val bitmap = ClahAccessibilityService.captureScreenshot()
                    ?: return@runCatching false
                try {
                    reader.isResultsScreen(bitmap)
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
        if (watchJob?.isActive != true) {
            watchJob = scope.launch(Dispatchers.Default) {
                watch(state)
            }
        }
    }

    fun cancel() {
        watchJob?.cancel()
        watchJob = null
        baselineJob?.cancel()
        baselineJob = null
        current = null
        scope.launch(Dispatchers.Main) {
            runCatching { showOverlay() }
        }
    }

    private suspend fun watch(state: SessionState) {
        val startedOnResults = withTimeoutOrNull(5_000) { state.baseline.await() } ?: false
        Log.i(TAG, "Baseline results screen=$startedOnResults")
        if (startedOnResults) {
            status("Already on a results screen; no new attack recorded.")
            clear(state)
            return
        }

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
                    delay(RETRY_DELAY_MS)
                    continue
                }
                val shotStart = System.nanoTime()
                val detectionBitmap = ClahAccessibilityService.captureScreenshot()
                val shotMs = (System.nanoTime() - shotStart) / 1_000_000L
                if (detectionBitmap == null) {
                    Log.i(TAG, "Poll screenshot failed after ${shotMs}ms")
                    delay(RETRY_DELAY_MS)
                    continue
                }
                val detectStart = System.nanoTime()
                val detected = isResultsScreen(detectionBitmap)
                val detectMs = (System.nanoTime() - detectStart) / 1_000_000L
                val elapsed = System.currentTimeMillis() - state.session.startedAt
                Log.i(
                    TAG,
                    "Poll at ${elapsed}ms: shot=${shotMs}ms detect=${detectMs}ms results=$detected"
                )
                if (!detected) {
                    detectionBitmap.recycle()
                    // No retry delay: the result screen can be dismissed within
                    // a second by the macro's Return Home gesture.
                    continue
                }

                Log.i(TAG, "Results screen detected; taking clean capture")
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
                            saveAndRecord(state, clean, cleanResult)
                            clear(state)
                            return
                        }
                    }
                    // The clean frame failed; the detection frame is still a
                    // valid read (the bubble may be in a corner).
                    val fallback = withTimeoutOrNull(READ_TIMEOUT_MS) {
                        reader.read(detectionBitmap)
                    }
                    if (fallback != null) {
                        saveAndRecord(state, detectionBitmap, fallback)
                        clear(state)
                        return
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    Log.w(TAG, "Results OCR failed", throwable)
                } finally {
                    cleanBitmap?.recycle()
                    detectionBitmap.recycle()
                    withContext(Dispatchers.Main) { runCatching { showOverlay() } }
                }
                delay(RETRY_DELAY_MS)
            }
            if (state.playbackEndedAt != null) {
                status("No results screen detected; no attack recorded.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.w(TAG, "Results capture failed", throwable)
            status("Results capture failed; no attack recorded.")
        } finally {
            withContext(Dispatchers.Main) { runCatching { showOverlay() } }
            if (current === state) current = null
        }
    }

    private suspend fun isResultsScreen(bitmap: Bitmap): Boolean =
        reader.isResultsScreen(bitmap)

    private suspend fun saveAndRecord(
        state: SessionState,
        bitmap: Bitmap,
        result: AppResultsReader.ReadResult
    ) {
        val session = state.session
        val playbackEndedAt = state.playbackEndedAt ?: System.currentTimeMillis()
        val interrupted = state.interrupted
        val saved = if (settingsStore.settings.value.saveResultsScreenshots) {
            withContext(Dispatchers.IO) {
                runCatching {
                    ResultsScreenshotStore(context).save(bitmap, session, result)
                }.getOrElse { throwable ->
                    Log.w(TAG, "Could not save result capture", throwable)
                    SavedCapture(null, null, null)
                }
            }
        } else {
            SavedCapture(null, null, null)
        }

        if (!ResultsCapturePolicy.mayAutoRecord(
                detectedResults = true,
                startedOnResults = false
            ) ||
            !AttackRecordPolicy.mayCreateRecord(AttackTrigger.DETECTED_RESULTS_SCREEN)
        ) {
            return
        }
        val record = ResultsToAttackRecord.build(
            session = session,
            read = result.read,
            playbackEndedAt = playbackEndedAt,
            interrupted = interrupted,
            screenshotPath = saved.png,
            sidecarPath = saved.json
        )
        val added = attackRepository.add(record)
        if (added == null) {
            Log.w(TAG, "Attack record was not added (duplicate or invalid)")
            status("Results screen read, but the attack record was not saved.")
            return
        }
        Log.i(
            TAG,
            "Attack record saved id=${added.id} outcome=${added.outcome} " +
                "lootCaptured=${added.hasLoot} screenshot=${added.resultsScreenshot ?: "-"}"
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

    private suspend fun status(message: String) {
        withContext(Dispatchers.Main) { onStatus(message) }
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
