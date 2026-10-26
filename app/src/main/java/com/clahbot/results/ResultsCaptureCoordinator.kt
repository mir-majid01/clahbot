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
 * Watches for the result screen after playback. The controller overlay is
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

    private data class SessionState(
        val session: CaptureSession,
        val baseline: CompletableDeferred<Boolean> = CompletableDeferred()
    )

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
    }

    fun finish(
        session: CaptureSession,
        playbackEndedAt: Long,
        interrupted: Boolean
    ) {
        val state = current ?: return
        if (state.session.id != session.id) return
        watchJob?.cancel()
        watchJob = scope.launch(Dispatchers.Default) {
            watch(state, playbackEndedAt, interrupted)
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

    private suspend fun watch(
        state: SessionState,
        playbackEndedAt: Long,
        interrupted: Boolean
    ) {
        val startedOnResults = withTimeoutOrNull(5_000) { state.baseline.await() } ?: false
        if (startedOnResults) {
            status("Already on a results screen; no new attack recorded.")
            clear(state)
            return
        }

        withContext(Dispatchers.Main) { hideOverlay() }
        delay(OVERLAY_HIDE_SETTLE_MS)
        try {
            val deadline = System.currentTimeMillis() + CAPTURE_WINDOW_MS
            while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
                if (!ClahAccessibilityService.isGameForeground()) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                val bitmap = ClahAccessibilityService.captureScreenshot()
                if (bitmap == null) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                val result = try {
                    withTimeoutOrNull(READ_TIMEOUT_MS) { reader.read(bitmap) }
                } catch (cancelled: CancellationException) {
                    bitmap.recycle()
                    throw cancelled
                } catch (throwable: Throwable) {
                    Log.w(TAG, "Results OCR failed", throwable)
                    null
                }
                if (result != null) {
                    try {
                        saveAndRecord(state, bitmap, result, playbackEndedAt, interrupted)
                    } finally {
                        bitmap.recycle()
                    }
                    clear(state)
                    return
                }
                bitmap.recycle()
                delay(RETRY_DELAY_MS)
            }
            status("No results screen detected; no attack recorded.")
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

    private suspend fun saveAndRecord(
        state: SessionState,
        bitmap: Bitmap,
        result: AppResultsReader.ReadResult,
        playbackEndedAt: Long,
        interrupted: Boolean
    ) {
        val session = state.session
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
        private const val CAPTURE_WINDOW_MS = 25_000L
        private const val RETRY_DELAY_MS = 800L
        private const val READ_TIMEOUT_MS = 25_000L
        private const val OVERLAY_HIDE_SETTLE_MS = 150L
    }
}
