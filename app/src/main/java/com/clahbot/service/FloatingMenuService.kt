package com.clahbot.service

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clahbot.R
import android.content.res.ColorStateList
import com.clahbot.data.AppEventLog
import com.clahbot.data.AttackRepository
import com.clahbot.data.BotState
import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.AttackStats
import com.clahbot.data.ControllerState
import com.clahbot.data.MacroRepository
import com.clahbot.data.MacroSelection
import com.clahbot.data.RecordingMode
import com.clahbot.data.SessionRepository
import com.clahbot.data.SettingsStore
import com.clahbot.data.models.SessionAggregator
import com.clahbot.data.models.totalDarkElixir
import com.clahbot.data.models.totalElixir
import com.clahbot.data.models.totalGold
import com.clahbot.data.models.GesturePath
import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.execution.BubbleCorner
import com.clahbot.execution.MacroPlayer
import com.clahbot.execution.MacroTimeline
import com.clahbot.execution.PlaybackBubblePlacement
import com.clahbot.input.BuiltGesture
import com.clahbot.input.InputAccessManager
import com.clahbot.input.InputEventSource
import com.clahbot.input.PassiveRecorder
import com.clahbot.input.ScreenGeometry
import com.clahbot.input.ScreenRect
import com.clahbot.results.AppResultsReader
import com.clahbot.results.CaptureSession
import com.clahbot.results.ResultCaptureGate
import com.clahbot.results.ResultsCaptureCoordinator
import com.clahbot.session.GameSessionTracker
import com.clahbot.ui.MacroInputActivity
import com.clahbot.ui.MacroInputRelay
import com.clahbot.ui.MainActivity
import com.clahbot.ui.SettingsActivity
import com.clahbot.ui.design.Ds
import com.clahbot.util.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Owns the floating controller, the passive touch recorder and macro playback.
 *
 * Passive recording reads getevent through Shizuku or root; no AutoClash
 * window is in the touch path. Capture-only mode still uses a full-screen
 * touch surface and deliberately blocks the game. Playback uses accessibility
 * gesture dispatch in both cases.
 */
@SuppressLint("SetTextI18n")
class FloatingMenuService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: MacroRepository
    private lateinit var settingsStore: SettingsStore
    private lateinit var attackRepository: AttackRepository
    private lateinit var sessionRepository: SessionRepository
    private var statsScope = STATS_SESSION
    private var scanButton: Button? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var controllerView: View? = null
    private var controllerParams: WindowManager.LayoutParams? = null
    private var captureView: View? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var passiveRecorder: PassiveRecorder? = null
    private var indicatorView: View? = null
    private var indicatorText: TextView? = null
    private var startingPassive = false
    private var activeRecordingMode: RecordingMode? = null

    @Volatile
    private var controllerBoundsSnapshot: ScreenRect? = null

    private var player: MacroPlayer? = null
    private val resultGate = ResultCaptureGate()
    private var manualWatch = false
    private var autoWatch = false
    private var autoWatchEnabled = true

    private var macros: List<Macro> = emptyList()
    private var selectedId: Long = -1L
    private var settingsView: View? = null
    private var settingsMacroId: Long = -1L
    private var pendingDelete: Macro? = null
    private var undoTicker: Runnable? = null

    private var recording: Boolean = false
    private var lastNotificationMs: Long = 0L
    private var lastStatusMs: Long = 0L

    // Recording never dispatches, so every captured gesture is physical.
    private var capturedGestures: Int = 0
    private var rejectedGestures: Int = 0
    private var recorded = mutableListOf<RecordedGesture>()
    private var recordStartMs: Long = 0L
    private var pausedAccumMs: Long = 0L
    private var pausedAtMs: Long = 0L
    private var gestureSeq: Int = 0

    private var downTime: Long = 0L
    private var pathPoints = mutableListOf<GesturePoint>()

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var notificationStarted: Boolean = false
    private var collapsed: Boolean = false
    private var page: Int = PAGE_CONTROLLER
    private var captureSession: CaptureSession? = null
    private lateinit var resultsReader: AppResultsReader
    private lateinit var resultsCapture: ResultsCaptureCoordinator

    @Volatile
    private var captureOverlayHidden = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!recording) return
            controllerView?.findViewById<TextView>(R.id.tvRecTimer)?.text =
                formatClock(elapsedRecordingMs())
            updateIndicator()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        GameSessionTracker.start(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repository = MacroRepository.get(this)
        settingsStore = SettingsStore.get(this)
        attackRepository = AttackRepository.get(this)
        sessionRepository = SessionRepository.get(this)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        AppEventLog.init(this, versionName)
        resultsReader = AppResultsReader(this)
        resultsCapture = ResultsCaptureCoordinator(
            context = this,
            scope = scope,
            attackRepository = attackRepository,
            settingsStore = settingsStore,
            reader = resultsReader,
            gate = resultGate,
            hideOverlay = { setCaptureOverlayHidden(true) },
            showOverlay = { setCaptureOverlayHidden(false) },
            onStatus = { message -> BotState.setStatus(message) },
            onRecordSaved = {
                AppEventLog.log(
                    "record callback playerRunning=${player?.isRunning() == true} " +
                        "repeat=${captureSession?.repeat ?: "-"} captureId=${captureSession?.id ?: "-"} " +
                        "manual=$manualWatch"
                )
                scope.launch {
                    attackRepository.refresh()
                    renderStats()
                    refreshUi()
                }
            },
            onWatchEnded = {
                if (manualWatch) {
                    manualWatch = false
                    captureSession = null
                    AppEventLog.log("manual watch cleared in UI")
                }
                if (autoWatch) {
                    autoWatch = false
                    captureSession = null
                    AppEventLog.log("auto watch cleared in UI")
                }
                if (autoWatchEnabled && !manualWatch && !recording &&
                    player?.isRunning() != true
                ) {
                    scope.launch {
                        delay(AUTO_RESTART_DELAY_MS)
                        startAutoWatch()
                    }
                }
                refreshUi()
            }
        )
        updateScreenSize()
        startForegroundCompat(getString(R.string.notification_idle))

        scope.launch { repository.refresh() }
        scope.launch { attackRepository.refresh() }
        scope.launch { sessionRepository.refresh() }
        scope.launch { attackRepository.records.collect { renderStats() } }
        scope.launch { sessionRepository.sessions.collect { renderStats() } }
        scope.launch { repository.macros.collect { onMacrosChanged(it) } }
        scope.launch {
            BotState.state.collect { state ->
                if (state == ControllerState.PLAYBACK_PAUSED) {
                    updateNotification(getString(R.string.notification_paused))
                }
                refreshUi()
            }
        }
        scope.launch { BotState.status.collect { onStatusChanged(it) } }
        scope.launch {
            BotState.startCountdown.collect { seconds ->
                if (seconds == null && BotState.state.value == ControllerState.PLAYING) {
                    macros.firstOrNull { it.id == BotState.activeMacroId.value }
                        ?.let { placeControllerForPlayback(it) }
                }
                refreshUi()
            }
        }
        scope.launch {
            settingsStore.settings.collect { settings ->
                val params = controllerParams ?: return@collect
                val view = controllerView ?: return@collect
                if (params.alpha != settings.controllerAlpha) {
                    params.alpha = settings.controllerAlpha
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                        // Window detached.
                    }
                }
                refreshUi()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val id = intent.getLongExtra(EXTRA_MACRO_ID, -1L)
                ensureController()
                if (id > 0) {
                    scope.launch {
                        val macro = repository.get(id)
                        if (macro == null) {
                            toast("Macro not found")
                        } else {
                            selectMacro(macro)
                            startPlayback(macro)
                        }
                    }
                }
            }

            ACTION_RESET_POSITION -> resetControllerPosition()
            ACTION_CLOSE -> requestClose()
            else -> ensureController()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
        refreshControllerBoundsSnapshot()
        if (!recording) return
        stopRecording()
        BotState.setStatus("Screen changed — recording stopped")
        toast("Recording stopped: screen changed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        MacroInputRelay.pending = null
        BotState.setInterrupted(true)
        captureSession = null
        autoWatch = false
        manualWatch = false
        autoWatchEnabled = false
        if (::resultsCapture.isInitialized) resultsCapture.cancel()
        if (::resultsReader.isInitialized) resultsReader.closeAsync()
        stopPlayback()
        stopRecording(silent = true)
        stopPassiveRecorder()
        removeIndicator()
        removeCapture()
        removeController()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ macro sync

    private fun onMacrosChanged(list: List<Macro>) {
        macros = list
        // Adopt a selection made on the dashboard, then validate it against the
        // library so a deleted macro can never stay selected.
        val desired = if (selectedId > 0) selectedId else BotState.selectedMacroId.value
        val reconciled = MacroSelection.reconcile(desired, list)
        if (reconciled != selectedId) {
            selectedId = reconciled
            BotState.setSelectedMacroId(reconciled)
        }
        if (settingsMacroId > 0 && list.none { it.id == settingsMacroId }) {
            closeMacroSettings()
        }

        val activeId = BotState.activeMacroId.value
        if (activeId > 0 && list.none { it.id == activeId }) {
            // The macro being played was deleted elsewhere: stop and do not resume.
            BotState.setInterrupted(true)
            stopPlayback()
            BotState.setState(ControllerState.READY)
            BotState.setStatus("The playing macro was deleted")
        }

        renderMacroList()
        refreshUi()
    }

    // ---------------------------------------------------------------- windows

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun ensureController() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Overlay permission is required")
            BotState.setStatus("Overlay permission missing")
            return
        }
        startAutoWatch()
        if (controllerView != null) {
            page = PAGE_CONTROLLER
            macros = repository.macros.value
            renderMacroList()
            expandController()
            return
        }

        updateScreenSize()
        val prefs = settingsStore.settings.value
        val controllerContext = scaledControllerContext(prefs.controllerScale)
        val view = LayoutInflater.from(controllerContext)
            .inflate(R.layout.overlay_controller, null)
        // Fit the list to the usable screen and keep the footer anchored below it.
        val scaledDensity = controllerContext.resources.displayMetrics.density
        val listDp = (screenHeight / scaledDensity - 340f).coerceIn(56f, 170f)
        view.findViewById<View>(R.id.macroScroll)?.let { scroll ->
            scroll.layoutParams = scroll.layoutParams.apply {
                height = (listDp * scaledDensity).toInt()
            }
        }
        applyBubbleSize(view, controllerContext, prefs.bubbleScale)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(96)
            alpha = prefs.controllerAlpha
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add controller window", e)
            toast("Could not show the controller")
            return
        }
        controllerView = view
        controllerParams = params
        bindController(view)
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            clampControllerToScreen()
            refreshControllerBoundsSnapshot()
        }
        view.post {
            clampControllerToScreen()
            refreshControllerBoundsSnapshot()
        }

        if (prefs.startMinimized) collapseController()

        page = PAGE_CONTROLLER
        BotState.setState(ControllerState.READY)
        BotState.setStatus("Controller ready")
        refreshUi()
        refreshMacros()
        // The repository may have emitted its current list before this window
        // existed; render the value we already have so the list is never blank.
        macros = repository.macros.value
        renderMacroList()
        scope.launch { attackRepository.refresh() }
    }

    private fun removeController() {
        val view = controllerView ?: return
        controllerView = null
        controllerParams = null
        collapsed = false
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Window already detached.
        }
    }

    private fun bindController(view: View) {
        val params = controllerParams ?: return

        view.findViewById<View>(R.id.controllerHeader)
            .setOnTouchListener(dragListener(params, onTap = {}))
        view.findViewById<View>(R.id.bubble)
            .setOnTouchListener(dragListener(params, onTap = { expandController() }))

        view.findViewById<ImageButton>(R.id.btnMinimize).setOnClickListener {
            haptic()
            collapseController()
        }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { requestClose() }
        view.findViewById<Button>(R.id.btnRecord).setOnClickListener {
            haptic()
            startRecording()
        }
        view.findViewById<Button>(R.id.btnPageController).setOnClickListener { setPage(PAGE_CONTROLLER) }
        view.findViewById<Button>(R.id.btnPageStats).setOnClickListener { setPage(PAGE_STATS) }
        view.findViewById<Button>(R.id.btnUndo).setOnClickListener {
            haptic()
            undoDelete()
        }

        view.findViewById<Button>(R.id.btnRecPause).setOnClickListener { toggleRecordingPause() }
        view.findViewById<Button>(R.id.btnRecFinish).setOnClickListener {
            haptic()
            stopRecording()
        }

        view.findViewById<Button>(R.id.btnRecordAgain).setOnClickListener {
            haptic()
            discardRecording(quiet = true)
            startRecording()
        }
        view.findViewById<Button>(R.id.btnDiscard).setOnClickListener {
            haptic()
            discardRecording()
        }
        view.findViewById<Button>(R.id.btnSave).setOnClickListener { saveRecording() }

        view.findViewById<Button>(R.id.btnStopCountdown).setOnClickListener {
            haptic()
            stopPlayback()
            BotState.setState(ControllerState.READY)
            BotState.setStatus("Playback stopped")
        }
        view.findViewById<Button>(R.id.btnPlayPause).setOnClickListener { togglePlaybackPause() }
        view.findViewById<Button>(R.id.btnStopPlayback).setOnClickListener {
            haptic()
            stopPlayback()
            BotState.setState(ControllerState.READY)
            BotState.setStatus("Playback stopped")
        }
    }

    private fun dragListener(
        params: WindowManager.LayoutParams,
        onTap: () -> Unit
    ): View.OnTouchListener {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val slop = dp(6)

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = clampX(startX + dx)
                        params.y = clampY(startY + dy)
                        val view = controllerView
                        if (view != null) {
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (_: Exception) {
                                // Window detached mid drag.
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    updateControllerPassthrough()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    updateControllerPassthrough()
                    true
                }

                else -> true
            }
        }
    }

    private fun clampX(value: Int): Int {
        val width = controllerView?.width?.takeIf { it > 0 } ?: dp(304)
        return value.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
    }

    private fun clampY(value: Int): Int {
        val height = controllerView?.height?.takeIf { it > 0 } ?: dp(220)
        return value.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
    }

    private fun collapseController() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        collapsed = true
        view.findViewById<View>(R.id.panel).visibility = View.GONE
        view.findViewById<View>(R.id.bubble).visibility = View.VISIBLE
        if ((controllerView?.width ?: 0) > 0) params.x = clampX(params.x)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Ignore.
        }
        view.post { updateControllerPassthrough() }
        refreshUi()
    }

    private fun expandController() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        collapsed = false
        view.findViewById<View>(R.id.bubble).visibility = View.GONE
        val panel = view.findViewById<View>(R.id.panel)
        panel.visibility = View.VISIBLE
        val panelWidth = panel.width.takeIf { it > 0 } ?: dp(304)
        val panelHeight = panel.height.takeIf { it > 0 } ?: dp(260)
        params.x = params.x.coerceIn(0, (screenWidth - panelWidth).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - panelHeight).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Ignore.
        }
        view.post { updateControllerPassthrough() }
        refreshUi()
    }

    private fun resetControllerPosition() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        params.x = dp(12)
        params.y = dp(96)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Ignore.
        }
        view.post { refreshControllerBoundsSnapshot() }
        toast("Controller position reset")
    }

    // -------------------------------------------------------------- recording

    private fun startRecording() {
        if (recording || startingPassive) return
        if (BotState.state.value.isPlayback) stopPlayback()
        if (controllerView == null) ensureController()
        if (controllerView == null) return
        // The always-on watcher must not poll or hide the overlay while the
        // user is recording a macro.
        abortCaptureSession()

        if (settingsStore.settings.value.recordingMode == RecordingMode.PASSIVE) {
            val access = InputAccessManager.status(this)
            if (!access.ready) {
                toast(InputAccessManager.unavailableMessage(this))
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
        }

        updateScreenSize()
        refreshControllerBoundsSnapshot()
        Log.i(TAG, "Recording start; controller bounds=$controllerBoundsSnapshot")
        page = PAGE_CONTROLLER

        recorded.clear()
        pathPoints.clear()
        capturedGestures = 0
        rejectedGestures = 0
        gestureSeq = 0
        pausedAccumMs = 0L
        pausedAtMs = 0L
        downTime = 0L
        lastStatusMs = 0L
        lastNotificationMs = 0L
        recordStartMs = SystemClock.uptimeMillis()
        BotState.clearRecording()

        when (settingsStore.settings.value.recordingMode) {
            RecordingMode.CAPTURE_ONLY -> startCaptureOnlyRecording()
            RecordingMode.PASSIVE -> startPassiveRecording()
        }
        refreshUi()
    }

    private fun startCaptureOnlyRecording() {
        activeRecordingMode = RecordingMode.CAPTURE_ONLY
        startingPassive = false
        recording = true
        BotState.setState(ControllerState.RECORDING)
        BotState.setStatus("Recording (blocks game input)")
        toast("Capture-only recording: the game will not respond to touches.")
        attachCaptureSurface()
        if (!recording) {
            activeRecordingMode = null
            return
        }
        setControllerKeepScreenOn(true)
        updateRecordingNotification()
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
    }

    private fun startPassiveRecording() {
        activeRecordingMode = RecordingMode.PASSIVE
        startingPassive = true
        recording = true
        BotState.clearRecording()
        BotState.setState(ControllerState.READY)
        BotState.setStatus("Starting capture — wait for REC")
        if (!addIndicator()) {
            recording = false
            startingPassive = false
            activeRecordingMode = null
            BotState.setState(ControllerState.READY)
            BotState.setStatus("Could not show the recording indicator")
            toast("Could not show the recording indicator")
            return
        }
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
        scope.launch { runPassiveStart() }
    }

    private suspend fun runPassiveStart() {
        var source: InputEventSource? = null
        var recorder: PassiveRecorder? = null
        try {
            source = withContext(Dispatchers.IO) {
                InputAccessManager.createSource(this@FloatingMenuService)
            }
            if (source == null) {
                onPassiveStartFailed(
                    InputAccessManager.unavailableMessage(this@FloatingMenuService)
                )
                return
            }
            if (!recording || activeRecordingMode != RecordingMode.PASSIVE) return

            val newRecorder = PassiveRecorder(
                source = source,
                geometry = {
                    ScreenGeometry(screenWidth, screenHeight, currentRotation())
                },
                controllerBounds = { controllerBoundsSnapshot },
                onGesture = { gesture ->
                    handler.post { onPassiveGesture(gesture) }
                },
                onError = { message ->
                    handler.post { onPassiveError(message) }
                }
            )
            recorder = newRecorder
            passiveRecorder = newRecorder

            val started = withContext(Dispatchers.IO) { newRecorder.start() }
            if (!started) {
                passiveRecorder = null
                onPassiveStartFailed(
                    "Passive capture could not start. Check Recording access in Settings."
                )
                return
            }
            if (!recording || activeRecordingMode != RecordingMode.PASSIVE) return

            startingPassive = false
            recordStartMs = SystemClock.uptimeMillis()
            setControllerKeepScreenOn(true)
            BotState.setState(ControllerState.RECORDING)
            BotState.setStatus("Recording passive — touches reach the game")
            Log.i(TAG, "Passive source started via ${source.label}")
            toast("Passive recording started. Swipe now.")
            updateRecordingNotification()
            updateIndicator()
            refreshUi()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            passiveRecorder = null
            onPassiveStartFailed(
                "Passive capture could not start: ${t.message ?: "unknown error"}"
            )
        } finally {
            if (!recording || activeRecordingMode != RecordingMode.PASSIVE) {
                passiveRecorder = null
                withContext(NonCancellable + Dispatchers.IO) {
                    recorder?.stop()
                    recorder?.close()
                    if (recorder == null) source?.close()
                }
            }
        }
    }

    private fun onPassiveStartFailed(message: String) {
        if (activeRecordingMode != RecordingMode.PASSIVE) return
        startingPassive = false
        recording = false
        activeRecordingMode = null
        handler.removeCallbacks(timerRunnable)
        removeIndicator()
        setControllerKeepScreenOn(false)
        updateNotification(getString(R.string.notification_idle))
        BotState.setState(ControllerState.READY)
        BotState.setStatus(message)
        toast(message)
        refreshUi()
    }

    private fun onPassiveGesture(gesture: BuiltGesture) {
        if (!recording || activeRecordingMode != RecordingMode.PASSIVE) return
        if (BotState.state.value == ControllerState.RECORDING_PAUSED) return
        Log.d(TAG, "Passive gesture: ${gesture.paths.size} path(s), ${gesture.durationMs} ms")
        commitPassiveGesture(gesture)
    }

    private fun onPassiveError(message: String) {
        if (!recording || activeRecordingMode != RecordingMode.PASSIVE) return
        Log.e(TAG, "Passive recording stopped: $message")
        stopRecording()
        BotState.setStatus("Recording stopped: $message")
        toast(message)
        refreshUi()
    }

    private fun commitPassiveGesture(built: BuiltGesture) {
        if (!recording || activeRecordingMode != RecordingMode.PASSIVE) return
        if (built.paths.isEmpty()) return
        val width = screenWidth
        val height = screenHeight
        if (width <= 0 || height <= 0) return

        val primary = built.paths.first()
        val sampled = MacroTimeline.decimate(primary.points)
        if (sampled.isEmpty()) return
        val deviation = MacroTimeline.maxDeviationPx(sampled)
        val primaryDuration = primary.durationMs.coerceAtLeast(1L)
        val type = MacroTimeline.classify(deviation, primaryDuration)
        val normalizedPrimary = MacroTimeline.normalize(sampled, width, height)
        if (normalizedPrimary.isEmpty()) return

        val normalizedPaths = if (built.paths.size > 1) {
            built.paths.take(MacroTimeline.MAX_STROKE_COUNT)
                .mapNotNull { path ->
                    val points = MacroTimeline.normalize(
                        MacroTimeline.decimate(path.points),
                        width,
                        height
                    )
                    if (points.size < 2) {
                        null
                    } else {
                        GesturePath(
                            startOffsetMs = path.startOffsetMs.coerceAtLeast(0L),
                            points = points
                        )
                    }
                }
                .takeIf { it.size > 1 }
                ?: emptyList()
        } else {
            emptyList()
        }

        val startInRecording =
            (built.startUptimeMs - recordStartMs - pausedAccumMs).coerceAtLeast(0L)
        val gesture = RecordedGesture(
            id = ++gestureSeq,
            timestampMs = startInRecording,
            gestureType = type,
            durationMs = built.durationMs.coerceIn(1L, MacroTimeline.MAX_STROKE_MS),
            normalizedStartX = normalizedPrimary.first().x,
            normalizedStartY = normalizedPrimary.first().y,
            normalizedEndX = normalizedPrimary.last().x,
            normalizedEndY = normalizedPrimary.last().y,
            points = normalizedPrimary,
            paths = normalizedPaths
        )
        recorded.add(gesture)
        capturedGestures++
        Log.i(TAG, "Captured $type with ${normalizedPrimary.size} points")
        BotState.setRecordedCount(recorded.size)
        updateRecordingInfo()
        val now = SystemClock.uptimeMillis()
        if (now - lastStatusMs > STATUS_THROTTLE_MS) {
            lastStatusMs = now
            BotState.setStatus("Recording passive — ${recorded.size} gesture(s)")
        }
        if (now - lastNotificationMs > NOTIFICATION_THROTTLE_MS) {
            lastNotificationMs = now
            updateRecordingNotification()
        }
    }

    private fun stopPassiveRecorder() {
        val recorder = passiveRecorder
        passiveRecorder = null
        if (recorder == null) return
        val wasStarting = startingPassive
        startingPassive = false
        if (wasStarting) {
            scope.launch(Dispatchers.IO) {
                recorder.stop()
                recorder.close()
            }
        } else {
            recorder.stop()
            scope.launch(Dispatchers.IO) { recorder.close() }
        }
    }

    private fun toggleRecordingPause() {
        if (startingPassive) {
            toast("Capture is starting…")
            return
        }
        val passive = activeRecordingMode == RecordingMode.PASSIVE
        when (BotState.state.value) {
            ControllerState.RECORDING -> {
                pausedAtMs = SystemClock.uptimeMillis()
                BotState.setState(ControllerState.RECORDING_PAUSED)
                BotState.setStatus(
                    if (passive) "Recording paused — game responds normally"
                    else "Recording paused"
                )
                pathPoints.clear()
                if (passive) {
                    passiveRecorder?.setPaused(true)
                } else {
                    // Capture-only: while paused the game receives input again.
                    setCaptureTouchable(false)
                }
            }

            ControllerState.RECORDING_PAUSED -> {
                pausedAccumMs += SystemClock.uptimeMillis() - pausedAtMs
                pausedAtMs = 0L
                BotState.setState(ControllerState.RECORDING)
                BotState.setStatus(
                    if (passive) "Recording passive — touches reach the game"
                    else "Recording (blocks game input)"
                )
                if (passive) {
                    passiveRecorder?.setPaused(false)
                } else {
                    setCaptureTouchable(true)
                }
            }

            else -> return
        }
        updateIndicator()
        updateRecordingNotification()
        refreshUi()
    }

    private fun controllerVisibleBounds(): Rect? {
        val root = controllerView ?: return null
        val panel = root.findViewById<View>(R.id.panel)
        val bubble = root.findViewById<View>(R.id.bubble)
        val target = when {
            collapsed && bubble.visibility == View.VISIBLE -> bubble
            !collapsed && panel.visibility == View.VISIBLE -> panel
            bubble.visibility == View.VISIBLE -> bubble
            else -> panel
        }
        if (target.width <= 0 || target.height <= 0) return null
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val pad = dp(10)
        return Rect(
            location[0] - pad,
            location[1] - pad,
            location[0] + target.width + pad,
            location[1] + target.height + pad
        )
    }

    private fun scaledControllerContext(scale: Float): Context {
        val config = Configuration(resources.configuration)
        config.densityDpi = (resources.displayMetrics.densityDpi * scale)
            .roundToInt()
            .coerceAtLeast(DisplayMetrics.DENSITY_LOW)
        return createConfigurationContext(config)
    }

    private fun applyBubbleSize(view: View, context: Context, bubbleScale: Float) {
        val density = context.resources.displayMetrics.density
        val bubblePx = (BUBBLE_SIZE_DP * bubbleScale * density)
            .roundToInt()
            .coerceAtLeast(1)
        val iconPx = (BUBBLE_ICON_DP * bubbleScale * density)
            .roundToInt()
            .coerceAtLeast(1)
        view.findViewById<View>(R.id.bubble)?.let { bubble ->
            bubble.layoutParams = bubble.layoutParams.apply {
                width = bubblePx
                height = bubblePx
            }
        }
        view.findViewById<View>(R.id.bubbleIcon)?.let { icon ->
            icon.layoutParams = icon.layoutParams.apply {
                width = iconPx
                height = iconPx
            }
        }
        view.findViewById<TextView>(R.id.bubbleStatus)?.textSize =
            (6f * bubbleScale).coerceAtLeast(4.5f)
    }

    private fun currentBubbleSizePx(): Int {
        val prefs = settingsStore.settings.value
        return (BUBBLE_SIZE_DP * prefs.controllerScale * prefs.bubbleScale *
            resources.displayMetrics.density)
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun clampControllerToScreen() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        if (view.width <= 0 || view.height <= 0) return
        if (screenWidth <= 0 || screenHeight <= 0) return
        val x = params.x.coerceIn(0, (screenWidth - view.width).coerceAtLeast(0))
        val y = params.y.coerceIn(0, (screenHeight - view.height).coerceAtLeast(0))
        if (x == params.x && y == params.y) return
        params.x = x
        params.y = y
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
    }

    private fun refreshControllerBoundsSnapshot() {
        val bounds = controllerVisibleBounds()
        controllerBoundsSnapshot = bounds?.let {
            ScreenRect(
                left = it.left.toFloat(),
                top = it.top.toFloat(),
                right = it.right.toFloat(),
                bottom = it.bottom.toFloat()
            )
        }
    }

    private fun updateControllerPassthrough() {
        refreshControllerBoundsSnapshot()
    }

    private fun setControllerKeepScreenOn(on: Boolean) {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val keep = on && settingsStore.settings.value.keepScreenAwake
        val updated = if (keep) params.flags or flag else params.flags and flag.inv()
        if (updated == params.flags) return
        params.flags = updated
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
    }

    private fun addIndicator(): Boolean {
        if (indicatorView != null) {
            updateIndicator()
            return true
        }
        val view = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xD9111827.toInt())
                setStroke(dp(1), 0x66EF4444)
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(96)
        }
        return try {
            windowManager.addView(view, params)
            indicatorView = view
            indicatorText = view
            updateIndicator()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show the recording indicator", e)
            false
        }
    }

    private fun removeIndicator() {
        val view = indicatorView ?: return
        indicatorView = null
        indicatorText = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Already detached.
        }
    }

    private fun updateIndicator() {
        val view = indicatorText ?: return
        val state = BotState.state.value
        view.text = when {
            state == ControllerState.RECORDING_PAUSED -> "❚❚ PAUSED"
            startingPassive -> "● STARTING"
            else -> "● REC " + formatClock(elapsedRecordingMs())
        }
        view.setTextColor(
            if (state == ControllerState.RECORDING_PAUSED) {
                0xFFFDE68A.toInt()
            } else {
                0xFFFCA5A5.toInt()
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int = windowManager.defaultDisplay.rotation

    private fun elapsedRecordingMs(): Long {
        val end = if (pausedAtMs > 0L) pausedAtMs else SystemClock.uptimeMillis()
        return (end - recordStartMs - pausedAccumMs).coerceAtLeast(0L)
    }

    /**
     * Adds the full screen capture window and then re-adds the controller so
     * the controller stays above it.
     */
    private fun attachCaptureSurface() {
        if (captureView == null) {
            val keepAwake = settingsStore.settings.value.keepScreenAwake
            val view = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            view.setOnTouchListener { _, event -> onCaptureTouch(event) }
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (keepAwake) {
                flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                flags,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                windowManager.addView(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add capture window", e)
                recording = false
                BotState.setState(ControllerState.READY)
                BotState.setStatus("Could not start recording")
                toast("Could not start recording")
                return
            }
            captureView = view
            captureParams = params
        }

        val view = controllerView ?: return
        val params = controllerParams ?: return
        try {
            windowManager.removeView(view)
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to raise controller above capture surface", e)
        }
    }

    /** Used only by pause: while paused the game receives input again. */
    private fun setCaptureTouchable(touchable: Boolean) {
        val view = captureView ?: return
        val params = captureParams ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val updated = if (touchable) params.flags and notTouchable.inv() else params.flags or notTouchable
        if (updated == params.flags) return
        params.flags = updated
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
    }

    private fun removeCapture() {
        val view = captureView ?: return
        captureView = null
        captureParams = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Already detached.
        }
    }

    private fun onCaptureTouch(event: MotionEvent): Boolean {
        if (!recording) return false
        if (BotState.state.value != ControllerState.RECORDING) return true
        val now = SystemClock.uptimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = now
                pathPoints = mutableListOf(GesturePoint(event.x, event.y, 0L))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val elapsed = now - downTime
                val last = pathPoints.lastOrNull()
                if (last == null) {
                    pathPoints.add(GesturePoint(event.x, event.y, elapsed))
                } else {
                    val dx = event.x - last.x
                    val dy = event.y - last.y
                    val farEnough = dx * dx + dy * dy >=
                        MacroTimeline.SAMPLE_DISTANCE_PX * MacroTimeline.SAMPLE_DISTANCE_PX
                    if (farEnough || elapsed - last.t >= 16L) {
                        pathPoints.add(GesturePoint(event.x, event.y, elapsed))
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val duration = (now - downTime).coerceAtLeast(1L)
                pathPoints.add(GesturePoint(event.x, event.y, duration))
                commitGesture(duration)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pathPoints.clear()
                return true
            }
        }
        return true
    }

    private fun commitGesture(durationMs: Long) {
        if (!recording) return
        val view = captureView
        if (view == null) {
            pathPoints.clear()
            return
        }
        val rawPoints = pathPoints.toList()
        pathPoints = mutableListOf()
        if (rawPoints.isEmpty()) return
        // event.x/y are relative to the capture view. Convert once to screen
        // coordinates before storing them, so a window that is not anchored at
        // (0,0) cannot shift the macro.
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        val screenPoints = if (origin[0] == 0 && origin[1] == 0) {
            rawPoints
        } else {
            rawPoints.map { GesturePoint(it.x + origin[0], it.y + origin[1], it.t) }
        }
        commitScreenGesture(screenPoints, downTime, durationMs)
    }

    /**
     * Normalizes screen-space samples against the same display bounds playback
     * uses. Live samples already arrive in screen coordinates; capture-only
     * samples are converted once by [commitGesture].
     */
    private fun commitScreenGesture(
        screenPoints: List<GesturePoint>,
        startUptimeMs: Long,
        durationMs: Long
    ) {
        if (!recording) return
        val width = screenWidth
        val height = screenHeight
        if (width <= 0 || height <= 0 || screenPoints.isEmpty()) return
        val sampled = MacroTimeline.decimate(screenPoints)
        val deviation = MacroTimeline.maxDeviationPx(sampled)
        val type = MacroTimeline.classify(deviation, durationMs)
        val normalized = MacroTimeline.normalize(sampled, width, height)
        if (normalized.isEmpty()) return

        val startInRecording = (startUptimeMs - recordStartMs - pausedAccumMs).coerceAtLeast(0L)
        val gesture = RecordedGesture(
            id = ++gestureSeq,
            timestampMs = startInRecording,
            gestureType = type,
            durationMs = durationMs.coerceIn(1L, MacroTimeline.MAX_STROKE_MS),
            normalizedStartX = normalized.first().x,
            normalizedStartY = normalized.first().y,
            normalizedEndX = normalized.last().x,
            normalizedEndY = normalized.last().y,
            points = normalized
        )
        recorded.add(gesture)
        capturedGestures++
        BotState.setRecordedCount(recorded.size)
        updateRecordingInfo()
        val now = SystemClock.uptimeMillis()
        if (now - lastStatusMs > STATUS_THROTTLE_MS) {
            lastStatusMs = now
            BotState.setStatus("Recording (blocks game input) — ${recorded.size} gesture(s)")
        }
        if (now - lastNotificationMs > NOTIFICATION_THROTTLE_MS) {
            lastNotificationMs = now
            updateRecordingNotification()
        }
    }

    private fun stopRecording(silent: Boolean = false) {
        if (!recording) return
        val wasStarting = startingPassive
        val recorder = passiveRecorder
        passiveRecorder = null
        startingPassive = false
        recording = false
        activeRecordingMode = null
        Log.i(TAG, "Recording stopped with ${recorded.size} gesture(s)")
        if (recorded.isEmpty()) rejectedGestures++
        pathPoints.clear()
        handler.removeCallbacks(timerRunnable)
        removeIndicator()
        removeCapture()
        setControllerKeepScreenOn(false)
        pausedAtMs = 0L
        pausedAccumMs = 0L

        if (recorder != null) {
            if (wasStarting) {
                scope.launch(Dispatchers.IO) {
                    recorder.stop()
                    recorder.close()
                }
            } else {
                recorder.stop()
                scope.launch(Dispatchers.IO) { recorder.close() }
            }
        }

        if (silent) {
            recorded.clear()
            BotState.clearRecording()
            return
        }

        // Finish must always reveal the review/save controls.
        page = PAGE_CONTROLLER
        if (collapsed) expandController()
        BotState.setRecordedCount(recorded.size)
        if (recorded.isEmpty()) {
            BotState.setState(ControllerState.READY)
            BotState.setStatus("Nothing recorded")
            toast("No gestures recorded")
        } else {
            BotState.setState(ControllerState.REVIEW)
            BotState.setStatus("Review recording")
            haptic()
        }
        updateNotification(getString(R.string.notification_idle))
        refreshUi()
        if (autoWatchEnabled) {
            scope.launch {
                delay(1_000)
                startAutoWatch()
            }
        }
    }

    private fun discardRecording(quiet: Boolean = false) {
        stopPassiveRecorder()
        removeIndicator()
        removeCapture()
        setControllerKeepScreenOn(false)
        handler.removeCallbacks(timerRunnable)
        recording = false
        startingPassive = false
        activeRecordingMode = null
        pausedAtMs = 0L
        pausedAccumMs = 0L
        recorded.clear()
        pathPoints.clear()
        BotState.clearRecording()
        BotState.setState(ControllerState.READY)
        BotState.setStatus("Recording discarded")
        if (!quiet) toast("Discarded")
        refreshUi()
    }

    // -------------------------------------------------------------- playback

    private fun startPlayback(macro: Macro) {
        if (recording) {
            toast("Finish the recording first")
            return
        }
        if (ClahAccessibilityService.instance == null) {
            toast(accessibilityHint())
            return
        }
        updateScreenSize()
        if (screenWidth <= 0 || screenHeight <= 0) {
            toast("Display size unavailable")
            return
        }

        if (macro.gestures.isEmpty()) {
            toast("This macro has no gestures")
            return
        }
        val adjusted = macro
        if (adjusted.orientation != currentOrientation()) {
            BotState.setStatus("Warning: macro was recorded in ${adjusted.orientation}")
        }

        val activeCaptureId = captureSession?.id
        if (activeCaptureId != null || manualWatch) {
            AppEventLog.log(
                "playback replacing capture id=${activeCaptureId ?: "-"} manual=$manualWatch"
            )
        }
        abortCaptureSession()
        BotState.setInterrupted(false)
        player?.stop()
        scope.launch {
            GameSessionTracker.ensureSession()
            GameSessionTracker.noteMacro(macro.id)
        }
        AppEventLog.log(
            "playback start macroId=${macro.id} gestures=${macro.gestures.size} " +
                "durationMs=${macro.durationMs} repeat=${macro.repeatMode} " +
                "speed=${macro.speedMultiplier} countdownMs=${macro.effectiveStartCountdownMs} " +
                "delayMs=${macro.effectiveRepeatDelayMs}"
        )
        // Place the icon before the first injected gesture can run. A macro with
        // a start countdown keeps the panel open so the countdown is visible.
        if (adjusted.effectiveStartCountdownMs <= 0L) placeControllerForPlayback(macro)
        scope.launch { resultsReader.warmUp() }
        scope.launch { repository.markPlayed(macro.id) }
        val startedAt = System.currentTimeMillis()
        val capture = CaptureSession(
            id = startedAt,
            startedAt = startedAt,
            macroId = macro.id,
            macroName = macro.name,
            speed = macro.speedMultiplier,
            repeat = macro.repeatMode
        )
        captureSession = capture
        resultsCapture.begin(capture)
        val newPlayer = MacroPlayer(screenWidth, screenHeight, resultGate)
        player = newPlayer
        newPlayer.play(adjusted, scope) {
            endAttackSession(interrupted = false)
        }
        if (!newPlayer.isRunning()) {
            abortCaptureSession()
        }
        updateNotification(getString(R.string.notification_playing, macro.name))
        refreshUi()
    }

    /**
     * While a macro is running, keep only the small icon and place it in the
     * top corner farthest from the macro's own touch points.
     */
    private fun placeControllerForPlayback(macro: Macro) {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        if (screenWidth <= 0 || screenHeight <= 0) return

        val landscape = screenWidth > screenHeight
        val topMargin = if (landscape) {
            dp(BUBBLE_TOP_LANDSCAPE_DP)
        } else {
            dp(BUBBLE_TOP_DP)
        }
        val bubbleSize = currentBubbleSizePx()
        val corner = PlaybackBubblePlacement.chooseCorner(
            points = macroTouchPoints(macro),
            width = screenWidth,
            height = screenHeight,
            bubbleSizePx = bubbleSize.toFloat(),
            sideMarginPx = dp(BUBBLE_MARGIN_DP).toFloat(),
            topMarginPx = topMargin.toFloat()
        )

        collapsed = true
        view.findViewById<View>(R.id.panel).visibility = View.GONE
        view.findViewById<View>(R.id.bubble).visibility = View.VISIBLE
        params.x = if (corner == BubbleCorner.TOP_RIGHT) {
            screenWidth - bubbleSize - dp(BUBBLE_MARGIN_DP)
        } else {
            dp(BUBBLE_MARGIN_DP)
        }
        params.y = topMargin
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
        view.post { refreshControllerBoundsSnapshot() }
        refreshUi()
    }

    private fun macroTouchPoints(macro: Macro): List<GesturePoint> {
        val points = ArrayList<GesturePoint>()
        for (gesture in macro.gestures) {
            val stored = gesture.paths.filter { it.points.isNotEmpty() }
            if (stored.isNotEmpty()) {
                stored.forEach { points.addAll(it.points) }
            } else {
                points.addAll(MacroTimeline.effectivePoints(gesture))
            }
        }
        return points
    }

    private fun togglePlaybackPause() {
        val active = player ?: return
        if (active.isPaused()) active.resume() else active.pause()
        updateNotification(
            if (active.isPaused()) {
                getString(R.string.notification_paused)
            } else {
                getString(
                    R.string.notification_playing,
                    BotState.activeMacroName.value ?: "macro"
                )
            }
        )
        refreshUi()
    }

    private fun stopPlayback() {
        val interrupted = BotState.interrupted.value
        AppEventLog.log("playback stop interrupted=$interrupted")
        player?.stop()
        player = null
        endAttackSession(interrupted)
        updateNotification(getString(R.string.notification_idle))
        refreshUi()
    }

    private fun setPage(next: Int) {
        page = next
        refreshUi()
        if (next == PAGE_STATS) {
            scope.launch {
                sessionRepository.refresh()
                attackRepository.refresh()
                renderStats()
            }
        }
    }

    private fun endAttackSession(interrupted: Boolean) {
        val capture = captureSession ?: return
        captureSession = null
        manualWatch = false
        // A finished playback is not evidence of an attack. Only a detected
        // results screen may create a record.
        Log.i(TAG, "Playback ended (interrupted=$interrupted); watching for results")
        AppEventLog.log("playback end interrupted=$interrupted; watching for results")
        BotState.setInterrupted(false)
        resultsCapture.finish(capture, System.currentTimeMillis(), interrupted)
    }

    private fun abortCaptureSession() {
        val hadSession = captureSession != null || manualWatch || autoWatch
        captureSession = null
        manualWatch = false
        autoWatch = false
        if (::resultsCapture.isInitialized) resultsCapture.cancel()
        if (hadSession) AppEventLog.log("capture session aborted")
    }

    private fun startAutoWatch() {
        if (!autoWatchEnabled || autoWatch || manualWatch || recording) return
        if (player?.isRunning() == true) return
        if (::resultsCapture.isInitialized && resultsCapture.isActive()) return
        if (ClahAccessibilityService.instance == null) return
        updateScreenSize()
        if (screenWidth <= 0 || screenHeight <= 0) return
        scope.launch { resultsReader.warmUp() }
        val startedAt = System.currentTimeMillis()
        val capture = CaptureSession(
            id = startedAt,
            startedAt = startedAt,
            macroId = 0L,
            macroName = "Auto watch",
            speed = 1f,
            repeat = "once"
        )
        captureSession = capture
        autoWatch = true
        scope.launch { GameSessionTracker.ensureSession() }
        resultsCapture.begin(capture)
        AppEventLog.log("auto watch start id=$startedAt")
        BotState.setStatus("Auto watch: waiting for a results screen")
        refreshUi()
    }

    private fun stopAutoWatch() {
        if (!autoWatch) return
        val watchId = captureSession?.id
        autoWatch = false
        autoWatchEnabled = false
        captureSession = null
        resultsCapture.cancel()
        AppEventLog.log("auto watch stop id=${watchId ?: "-"}")
        BotState.setStatus("Auto watch stopped")
        refreshUi()
    }

    // ---------------------------------------------------------- manual watch

    private fun toggleManualWatch() {
        if (autoWatch) {
            stopAutoWatch()
        } else if (manualWatch) {
            stopManualWatch()
        } else {
            startManualWatch()
        }
    }

    private fun startManualWatch() {
        if (recording || BotState.state.value == ControllerState.REVIEW) {
            toast("Finish the recording first")
            return
        }
        if (player?.isRunning() == true) {
            toast("Stop playback first")
            return
        }
        if (ClahAccessibilityService.instance == null) {
            toast(accessibilityHint())
            return
        }
        updateScreenSize()
        if (screenWidth <= 0 || screenHeight <= 0) {
            toast("Display size unavailable")
            return
        }
        abortCaptureSession()
        BotState.setInterrupted(false)
        scope.launch { resultsReader.warmUp() }
        val startedAt = System.currentTimeMillis()
        val capture = CaptureSession(
            id = startedAt,
            startedAt = startedAt,
            macroId = 0L,
            macroName = "Manual watch",
            speed = 1f,
            repeat = "once"
        )
        captureSession = capture
        manualWatch = true
        scope.launch { GameSessionTracker.ensureSession() }
        resultsCapture.begin(capture)
        AppEventLog.log("manual watch start id=$startedAt")
        BotState.setStatus("Watching for results — play your attack")
        collapseForWatch()
        refreshUi()
    }

    private fun stopManualWatch() {
        if (!manualWatch) return
        val watchId = captureSession?.id
        manualWatch = false
        autoWatchEnabled = false
        captureSession = null
        resultsCapture.cancel()
        AppEventLog.log("manual watch stop id=${watchId ?: "-"}")
        BotState.setStatus("Manual watch stopped")
        refreshUi()
    }

    private fun collapseForWatch() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        collapsed = true
        view.findViewById<View>(R.id.panel).visibility = View.GONE
        view.findViewById<View>(R.id.bubble).visibility = View.VISIBLE
        if (screenWidth > 0 && screenHeight > 0) {
            val landscape = screenWidth > screenHeight
            params.x = dp(BUBBLE_MARGIN_DP)
            params.y = if (landscape) dp(BUBBLE_TOP_LANDSCAPE_DP) else dp(BUBBLE_TOP_DP)
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
                // Window detached.
            }
        }
        view.post { refreshControllerBoundsSnapshot() }
        refreshUi()
    }

    private fun accessibilityHint(): String =
        if (PermissionHelper.isAccessibilityEnabled(this)) {
            "Accessibility is enabled but still connecting — try again in a second"
        } else {
            "Enable the AutoClash accessibility service first"
        }

    private fun renderStats() {
        if (page != PAGE_STATS) return
        val view = controllerView ?: return
        val content = view.findViewById<LinearLayout>(R.id.statsContent) ?: return
        content.removeAllViews()
        content.setPadding(dp(2), dp(2), dp(2), dp(2))

        val all = AttackStats.confirmedOnly(attackRepository.records.value)
            .sortedByDescending { it.startedAt }
        val sessions = sessionRepository.sessions.value
        val current = sessions.firstOrNull { it.active } ?: sessions.firstOrNull()
        val scoped = if (statsScope == STATS_SESSION) {
            if (current == null) emptyList() else all.filter { it.id in current.attackIds }
        } else {
            all
        }
        val totals = SessionAggregator.totals(scoped)

        content.addView(scopeSegment())
        content.addView(Ds.spacer(this, 8))

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.addView(
            resourceTile(R.drawable.ic_gold, "Gold", totals.totalGold, totals.bonusGold),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        )
        tiles.addView(
            resourceTile(R.drawable.ic_elixir, "Elixir", totals.totalElixir, totals.bonusElixir),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        )
        tiles.addView(
            resourceTile(
                R.drawable.ic_dark,
                "Dark elixir",
                totals.totalDarkElixir,
                totals.bonusDarkElixir
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(tiles)
        content.addView(statsMetaRow(totals))

        if (totals.incompleteAttacks > 0) {
            content.addView(
                Ds.text(this, 10f, Ds.WARN).apply {
                    text = "${totals.incompleteAttacks} attack(s) incomplete — excluded from totals"
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }

        content.addView(
            Ds.text(this, 11f, Ds.MUTED, caps = true).apply {
                text = "Recent"
                setPadding(0, dp(12), 0, dp(4))
            }
        )
        if (scoped.isEmpty()) {
            content.addView(
                Ds.text(this, 11f, Ds.MUTED).apply {
                    text = if (statsScope == STATS_SESSION) {
                        "No session yet. Switch to All time or launch Clash."
                    } else {
                        "No attacks yet."
                    }
                    setPadding(0, dp(4), 0, dp(8))
                }
            )
        } else {
            scoped.take(5).forEachIndexed { index, record ->
                content.addView(recentAttackRow(record))
                if (index < minOf(4, scoped.size - 1)) content.addView(Ds.divider(this))
            }
        }

        val seeAll = Ds.text(this, 12f, Ds.LINK, bold = true).apply {
            text = "See all history"
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setOnClickListener {
                startActivity(
                    Intent(this@FloatingMenuService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        content.addView(seeAll)

        scanButton = Button(this).apply {
            text = "Scan now"
            textSize = 11f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setTextColor(Ds.TEXT)
            background = Ds.rounded(Ds.BUTTON, 10, this@FloatingMenuService)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { topMargin = dp(4) }
            setOnClickListener {
                haptic()
                toggleManualWatch()
            }
        }
        content.addView(scanButton)
        updateScanButton()
    }

    private fun scopeSegment(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ds.rounded(Ds.INPUT, 10, this@FloatingMenuService)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        listOf(STATS_SESSION to "Session", STATS_ALL to "All time").forEach { (scope, label) ->
            val selected = statsScope == scope
            val button = Ds.text(this, 12f, Ds.MUTED, bold = true).apply {
                text = label
                gravity = Gravity.CENTER
                minHeight = dp(42)
                setPadding(dp(16), 0, dp(16), 0)
                background = Ds.rounded(
                    if (selected) Ds.ACCENT else android.graphics.Color.TRANSPARENT,
                    8,
                    this@FloatingMenuService
                )
                setTextColor(if (selected) android.graphics.Color.WHITE else Ds.MUTED)
                setOnClickListener {
                    if (statsScope != scope) {
                        statsScope = scope
                        renderStats()
                    }
                }
            }
            row.addView(button)
        }
        return row
    }

    private fun resourceTile(
        iconRes: Int,
        label: String,
        total: Long,
        bonus: Long
    ): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.rounded(Ds.PANEL, Ds.RADIUS_CARD, this@FloatingMenuService, Ds.PANEL_BORDER)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            isClickable = true
            setOnClickListener {
                android.widget.Toast.makeText(
                    this@FloatingMenuService,
                    "$label ${String.format(Locale.US, "%,d", total)} " +
                        "(loot ${String.format(Locale.US, "%,d", total - bonus)} + bonus " +
                        "${String.format(Locale.US, "%,d", bonus)})",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        tile.addView(ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        tile.addView(Ds.text(this, 20f, Ds.TEXT, bold = true).apply {
            text = Ds.compactNumber(total)
            setPadding(0, dp(6), 0, 0)
            fontFeatureSettings = "tnum"
        })
        tile.addView(Ds.text(this, 10f, Ds.MUTED).apply {
            text = if (bonus > 0L) "+${Ds.compactNumber(bonus)} bonus" else "no bonus"
        })
        return tile
    }

    private fun statsMetaRow(totals: com.clahbot.data.models.SessionTotals): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun cell(label: String, value: String): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(Ds.text(this@FloatingMenuService, 11f, Ds.MUTED).apply {
                    text = label
                })
                addView(Ds.text(this@FloatingMenuService, 15f, Ds.TEXT, bold = true).apply {
                    text = value
                    fontFeatureSettings = "tnum"
                })
            }
        val average = if (totals.attackCount > 0) {
            totals.durationMs / totals.attackCount
        } else {
            0L
        }
        row.addView(cell("Attacks", totals.attackCount.toString()))
        row.addView(cell("Avg / attack", formatDuration(average)))
        row.addView(cell("Time", formatSpan(totals.durationMs)))
        return row
    }

    private fun recentAttackRow(record: AttackRecord): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(Ds.text(this, 13f, Ds.TEXT, bold = true).apply {
            text = formatClockOfDay(record.startedAt)
            fontFeatureSettings = "tnum"
            setPadding(0, 0, dp(6), 0)
        })
        statusChip(record)?.let { title.addView(it) }
        left.addView(title)
        left.addView(Ds.text(this, 11f, Ds.MUTED).apply {
            text = formatDuration(record.durationMs)
        })
        row.addView(left)

        val loot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        loot.addView(lootValue(R.drawable.ic_gold, record.gold))
        loot.addView(lootValue(R.drawable.ic_elixir, record.elixir))
        loot.addView(lootValue(R.drawable.ic_dark, record.darkElixir))
        row.addView(loot)
        return row
    }

    private fun statusChip(record: AttackRecord): TextView? {
        val (text, color, bg) = when {
            record.needsReview -> Triple("review", Ds.WARN, Ds.WARN_BG)
            !record.hasLoot -> Triple("not captured", Ds.WARN, Ds.WARN_BG)
            else -> return null
        }
        return Ds.text(this, 9f, color, bold = true).apply {
            this.text = text
            background = Ds.pill(bg, this@FloatingMenuService)
            setPadding(dp(7), dp(2), dp(7), dp(2))
        }
    }

    private fun lootValue(iconRes: Int, value: Long?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, 0, 0)
            addView(ImageView(this@FloatingMenuService).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
            addView(Ds.text(this@FloatingMenuService, 11f, Ds.TEXT, bold = true).apply {
                text = value?.let { Ds.compactNumber(it) } ?: "—"
                setPadding(dp(3), 0, 0, 0)
                fontFeatureSettings = "tnum"
            })
        }

    private fun formatSpan(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes >= 60L) {
            "${minutes / 60L}h ${minutes % 60L}m"
        } else {
            "${minutes}m"
        }
    }

    private fun updateScanButton() {
        scanButton?.apply {
            val state = BotState.state.value
            val captureBusy = resultsCapture.isActive() && !manualWatch && !autoWatch
            text = when {
                manualWatch -> "Stop scan"
                autoWatch -> "Stop auto scan"
                else -> "Scan now"
            }
            isEnabled = manualWatch || autoWatch ||
                (!state.isBusy && !captureBusy)
        }
    }

    private fun formatClockOfDay(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(timestamp))

    // ----------------------------------------------------------- macro library

    private fun refreshMacros() {
        scope.launch { repository.refresh() }
    }

    private fun selectMacro(macro: Macro) {
        selectedId = macro.id
        BotState.setSelectedMacroId(macro.id)
        renderMacroList()
        refreshUi()
    }

    private fun renderMacroList() {
        val view = controllerView ?: return
        val container = view.findViewById<LinearLayout>(R.id.macroList) ?: run {
            Log.e(TAG, "macroList container missing")
            return
        }
        container.removeAllViews()
        Log.i(TAG, "Rendering ${macros.size} macro(s) in the controller")

        view.findViewById<TextView>(R.id.tvLibraryLabel)?.apply {
            text = if (macros.isEmpty()) "Macros" else "Macros · ${macros.size}"
            isAllCaps = true
            letterSpacing = 0.08f
            setTextColor(Ds.MUTED)
            textSize = 11f
        }

        if (macros.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No macros yet. Record one to get started."
                setTextColor(Ds.MUTED)
                textSize = 11f
                setPadding(dp(6), dp(12), dp(6), dp(12))
            })
            return
        }

        for (macro in macros) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(2), dp(7))
                if (macro.id == selectedId) {
                    background = Ds.rounded(
                        Ds.SELECTED_ROW,
                        Ds.RADIUS_ROW,
                        this@FloatingMenuService,
                        Ds.SELECTED_ROW_BORDER
                    )
                }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener { selectMacro(macro) }
            }
            info.addView(TextView(this).apply {
                text = if (macro.pinned) "PIN · ${macro.name}" else macro.name
                setTextColor(Ds.TEXT)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = "${macro.gestures.size} gestures · ${formatDuration(macro.durationMs)}"
                setTextColor(Ds.MUTED)
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, dp(5))
            })
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            chips.addView(macroChip(MacroTimeline.formatSpeed(macro.speedMultiplier)))
            chips.addView(macroChip(MacroTimeline.repeatLabel(macro.repeatMode)))
            chips.addView(macroChip(macroDelayLabel(macro)))
            info.addView(chips)
            row.addView(info)
            val play = ImageButton(this).apply {
                setImageResource(R.drawable.ic_play)
                contentDescription = "Play ${macro.name}"
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Ds.PLAY)
                }
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(13), dp(13), dp(13), dp(13))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginStart = dp(4)
                }
                setOnClickListener {
                    haptic()
                    selectedId = macro.id
                    BotState.setSelectedMacroId(macro.id)
                    renderMacroList()
                    startPlayback(macro)
                }
            }
            row.addView(play)
            row.addView(iconButton(R.drawable.ic_gear, "Settings for ${macro.name}") {
                haptic()
                showMacroSettings(macro)
            })
            container.addView(row)
            container.addView(Ds.spacer(this, 2))
        }
    }

    private fun macroChip(text: String): TextView =
        Ds.text(this, 11f, Ds.MUTED, bold = true).apply {
            this.text = text
            background = Ds.pill(Ds.BUTTON, this@FloatingMenuService)
            setPadding(dp(9), dp(3), dp(9), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(4) }
        }

    private fun iconButton(iconRes: Int, description: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            background = getDrawable(R.drawable.bg_icon_button)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = dp(2)
            }
            setOnClickListener { onClick() }
        }

    private fun macroDelayLabel(macro: Macro): String {
        val delay = macro.effectiveRepeatDelayMs
        return if (delay <= 0L) "no gap" else formatDelaySeconds(delay) + "s gap"
    }

    private fun saveRecording() {
        if (recorded.isEmpty()) {
            toast("Nothing to save")
            return
        }
        val defaultName = "Macro " + SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date())

        fun persist(name: String) {
            val gestures = recorded.toList()
            val prefs = settingsStore.settings.value
            val macro = Macro(
                id = nextMacroId(),
                name = name,
                createdAt = System.currentTimeMillis(),
                durationMs = MacroTimeline.totalDuration(gestures),
                deviceWidth = screenWidth,
                deviceHeight = screenHeight,
                orientation = currentOrientation(),
                gestures = gestures,
                repeatMode = prefs.defaultRepeat,
                speedMultiplier = MacroTimeline.clampSpeed(prefs.defaultSpeed)
            )
            Log.i(TAG, "Saving \"$name\" with ${gestures.size} gesture(s)")
            scope.launch {
                val saved = repository.save(macro)
                if (saved == null) {
                    Log.e(TAG, "Macro save failed for \"$name\"")
                    toast("Could not save macro")
                    return@launch
                }
                Log.i(TAG, "Macro saved id=${saved.id} with ${saved.gestures.size} gesture(s)")
                recorded.clear()
                BotState.clearRecording()
                selectedId = saved.id
                BotState.setSelectedMacroId(saved.id)
                BotState.setState(ControllerState.READY)
                BotState.setStatus("Saved \"${saved.name}\"")
                refreshUi()
                toast("Macro saved")
            }
        }

        showInputDialog(
            title = "Save macro",
            initial = defaultName,
            onFailure = {
                // Never lose a finished recording just because the name dialog failed.
                persist(defaultName)
                toast("Name dialog unavailable — saved with the default name")
            }
        ) { name -> persist(name) }
    }

    // ------------------------------------------------------ macro settings

    private data class SettingsDraft(
        var name: String,
        var speed: Float,
        var repeat: String,
        var repeatCount: Int,
        var delayMs: Long,
        var countdownSec: Int,
        var stopOnBackground: Boolean,
        var stopMinutes: Int?,
        var stopAttacks: Int?,
        var pinned: Boolean
    )

    private fun showMacroSettings(macro: Macro) {
        val view = controllerView ?: return
        val host = view.findViewById<FrameLayout>(R.id.settingsHost) ?: return
        if (settingsView != null) closeMacroSettings()

        val draft = SettingsDraft(
            name = macro.name,
            speed = MacroTimeline.clampSpeed(macro.speedMultiplier),
            repeat = macro.repeatMode,
            repeatCount = MacroTimeline.repeatCount(macro.repeatMode),
            delayMs = macro.effectiveRepeatDelayMs,
            countdownSec = (macro.effectiveStartCountdownMs / 1000L).toInt(),
            stopOnBackground = macro.stopOnBackground,
            stopMinutes = macro.effectiveStopAfterMinutes,
            stopAttacks = macro.effectiveStopAfterAttacks,
            pinned = macro.pinned
        )

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.removeAllViews()
        host.addView(root)
        settingsView = root
        settingsMacroId = macro.id
        host.visibility = View.VISIBLE
        view.findViewById<View>(R.id.pageTabs).visibility = View.GONE
        view.findViewById<View>(R.id.contentFrame).visibility = View.GONE

        val error = Ds.text(this, 10f, Ds.DANGER_TEXT).apply { visibility = View.GONE }

        fun showError(message: String) {
            error.text = message
            error.visibility = View.VISIBLE
        }

        fun clearError() {
            error.visibility = View.GONE
        }

        fun draftMacro(): Macro = macro.copy(
            name = draft.name.trim().take(40),
            speedMultiplier = MacroTimeline.clampSpeed(draft.speed),
            repeatMode = if (MacroTimeline.isCustomRepeat(draft.repeat)) {
                MacroTimeline.customRepeatMode(draft.repeatCount)
            } else {
                draft.repeat
            },
            repeatDelayMs = draft.delayMs.coerceIn(0L, Macro.MAX_REPEAT_DELAY_MS),
            startCountdownMs = draft.countdownSec.coerceIn(0, 30) * 1000L,
            stopOnBackground = draft.stopOnBackground,
            stopAfterMinutes = draft.stopMinutes,
            stopAfterAttacks = draft.stopAttacks,
            pinned = draft.pinned
        )

        fun persist(onSaved: (Macro) -> Unit) {
            clearError()
            if (draft.name.isBlank()) {
                showError("Enter a macro name")
                return
            }
            scope.launch {
                val saved = repository.save(draftMacro())
                if (saved == null) showError("Save failed") else onSaved(saved)
            }
        }

        fun actionButton(text: String, danger: Boolean = false, onClick: () -> Unit): Button =
            Button(this).apply {
                this.text = text
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                setTextColor(if (danger) Ds.DANGER_TEXT else Ds.TEXT)
                background = Ds.rounded(
                    if (danger) Ds.PANEL else Ds.BUTTON,
                    10,
                    this@FloatingMenuService,
                    if (danger) Ds.DANGER_OUTLINE else null
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    haptic()
                    onClick()
                }
            }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        header.addView(iconButton(R.drawable.ic_back, "Back to macros") { closeMacroSettings() })
        header.addView(
            Ds.text(this, 15f, Ds.TEXT, bold = true).apply {
                text = macro.name
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            }
        )
        header.addView(Ds.text(this, 12f, Ds.MUTED).apply { text = "Settings" })

        fun labelRow(label: String, control: View): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(
                Ds.text(this, 13f, Ds.MUTED).apply { text = label },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(control)
            return row
        }

        fun stepper(
            valueText: () -> String,
            enabled: Boolean = true,
            onMinus: () -> Unit,
            onPlus: () -> Unit
        ): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val value = Ds.text(
                this,
                14f,
                if (enabled) Ds.TEXT else 0xFF5B6B8A.toInt(),
                bold = true
            ).apply {
                gravity = Gravity.CENTER
                minWidth = dp(54)
                minHeight = dp(48)
                text = valueText()
            }
            fun refresh() {
                value.text = valueText()
                value.setTextColor(if (enabled) Ds.TEXT else 0xFF5B6B8A.toInt())
            }
            val minus = iconButton(R.drawable.ic_minus, "Decrease") {
                if (enabled) {
                    onMinus()
                    refresh()
                    clearError()
                }
            }
            val plus = iconButton(R.drawable.ic_plus, "Increase") {
                if (enabled) {
                    onPlus()
                    refresh()
                    clearError()
                }
            }
            box.addView(minus)
            box.addView(value)
            box.addView(plus)
            return box
        }

        fun enabledSwitch(checked: Boolean, onChange: (Boolean) -> Unit): Switch =
            Switch(this).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value ->
                    haptic()
                    onChange(value)
                }
            }

        fun segmented(
            options: List<Pair<String, String>>,
            isSelected: (String) -> Boolean,
            onSelect: (String) -> Unit
        ): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Ds.rounded(Ds.INPUT, 10, this@FloatingMenuService)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val buttons = mutableListOf<TextView>()
            fun paint() {
                buttons.forEach { button ->
                    val selected = isSelected(button.tag as String)
                    button.background = Ds.rounded(
                        if (selected) Ds.ACCENT else android.graphics.Color.TRANSPARENT,
                        8,
                        this@FloatingMenuService
                    )
                    button.setTextColor(
                        if (selected) android.graphics.Color.WHITE else Ds.MUTED
                    )
                }
            }
            options.forEach { (key, label) ->
                val button = Ds.text(this, 12f, Ds.MUTED, bold = true).apply {
                    tag = key
                    text = label
                    gravity = Gravity.CENTER
                    setPadding(dp(10), 0, dp(10), 0)
                    minHeight = dp(42)
                    minWidth = dp(54)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(42)
                    )
                    setOnClickListener {
                        haptic()
                        onSelect(key)
                        paint()
                        clearError()
                    }
                }
                buttons.add(button)
                row.addView(button)
            }
            paint()
            return row
        }

        var repeatCountAlpha: TextView? = null

        fun textField(value: String, onEdit: () -> Unit): TextView =
            Ds.text(this, 14f, Ds.TEXT).apply {
                text = value
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), 0, dp(12), 0)
                minHeight = dp(44)
                minWidth = dp(150)
                gravity = Gravity.CENTER_VERTICAL
                background = Ds.rounded(Ds.INPUT, 10, this@FloatingMenuService, Ds.PANEL_BORDER)
                isClickable = true
                contentDescription = "Name: $value"
                setOnClickListener {
                    haptic()
                    onEdit()
                }
            }

        fun openNameEditor(current: String) {
            openTextEditor("Macro name", current, MacroInputActivity.KIND_NAME) { result ->
                val trimmed = result?.trim()?.take(40).orEmpty()
                if (trimmed.isNotEmpty()) {
                    draft.name = trimmed
                    showMacroSettings(draftMacro())
                }
            }
        }

        val nameField = textField(draft.name) { openNameEditor(draft.name) }
        val speedStepper = stepper(
            valueText = { MacroTimeline.formatSpeed(draft.speed) },
            onMinus = { draft.speed = MacroTimeline.stepSpeed(draft.speed, -1) },
            onPlus = { draft.speed = MacroTimeline.stepSpeed(draft.speed, 1) }
        )
        val repeatSegment = segmented(
            options = listOf(
                Macro.REPEAT_ONCE to "Once",
                Macro.REPEAT_INFINITE to "Loop",
                "custom" to "Custom"
            ),
            isSelected = {
                if (it == "custom") MacroTimeline.isCustomRepeat(draft.repeat)
                else draft.repeat == it
            },
            onSelect = {
                draft.repeat = if (it == "custom") {
                    MacroTimeline.customRepeatMode(draft.repeatCount)
                } else {
                    it
                }
                repeatCountAlpha?.alpha =
                    if (MacroTimeline.isCustomRepeat(draft.repeat)) 1f else 0.45f
            }
        )
        val repeatCountStepper = stepper(
            valueText = { draft.repeatCount.toString() },
            onMinus = {
                draft.repeatCount = (draft.repeatCount - 1).coerceAtLeast(1)
                draft.repeat = MacroTimeline.customRepeatMode(draft.repeatCount)
            },
            onPlus = {
                draft.repeatCount = (draft.repeatCount + 1).coerceAtMost(Macro.MAX_CUSTOM_REPEAT)
                draft.repeat = MacroTimeline.customRepeatMode(draft.repeatCount)
            }
        )
        repeatCountAlpha = repeatCountStepper.getChildAt(1) as? TextView
        val delayStepper = stepper(
            valueText = { formatDelaySeconds(draft.delayMs) + " s" },
            onMinus = { draft.delayMs = (draft.delayMs - 200L).coerceAtLeast(0L) },
            onPlus = { draft.delayMs = (draft.delayMs + 200L).coerceAtMost(Macro.MAX_REPEAT_DELAY_MS) }
        )
        val countdownStepper = stepper(
            valueText = { "${draft.countdownSec} s" },
            onMinus = { draft.countdownSec = (draft.countdownSec - 1).coerceAtLeast(0) },
            onPlus = { draft.countdownSec = (draft.countdownSec + 1).coerceAtMost(30) }
        )

        val minutesStepper = stepper(
            valueText = { (draft.stopMinutes ?: 30).toString() },
            enabled = draft.stopMinutes != null,
            onMinus = {
                draft.stopMinutes = ((draft.stopMinutes ?: 30) - 1)
                    .coerceIn(1, Macro.MAX_STOP_AFTER_MINUTES)
            },
            onPlus = {
                draft.stopMinutes = ((draft.stopMinutes ?: 30) + 1)
                    .coerceIn(1, Macro.MAX_STOP_AFTER_MINUTES)
            }
        )
        val minutesSwitch = enabledSwitch(draft.stopMinutes != null) { checked ->
            draft.stopMinutes = if (checked) 30 else null
            minutesStepper.alpha = if (checked) 1f else 0.45f
        }
        minutesStepper.alpha = if (draft.stopMinutes != null) 1f else 0.45f

        val attacksStepper = stepper(
            valueText = { (draft.stopAttacks ?: 10).toString() },
            enabled = draft.stopAttacks != null,
            onMinus = {
                draft.stopAttacks = ((draft.stopAttacks ?: 10) - 1)
                    .coerceIn(1, Macro.MAX_STOP_AFTER_ATTACKS)
            },
            onPlus = {
                draft.stopAttacks = ((draft.stopAttacks ?: 10) + 1)
                    .coerceIn(1, Macro.MAX_STOP_AFTER_ATTACKS)
            }
        )
        val attacksSwitch = enabledSwitch(draft.stopAttacks != null) { checked ->
            draft.stopAttacks = if (checked) 10 else null
            attacksStepper.alpha = if (checked) 1f else 0.45f
        }
        attacksStepper.alpha = if (draft.stopAttacks != null) 1f else 0.45f

        val pinSwitch = enabledSwitch(draft.pinned) { draft.pinned = it }
        val stopBackgroundSwitch = enabledSwitch(draft.stopOnBackground) {
            draft.stopOnBackground = it
        }

        val landscape = screenWidth > screenHeight
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val columns = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        if (landscape) {
            columns.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            columns.addView(
                right,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(24)
                }
            )
        } else {
            columns.addView(left, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            columns.addView(right, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        fun addRow(column: LinearLayout, label: String, control: View) {
            column.addView(labelRow(label, control))
            column.addView(Ds.divider(this))
        }

        addRow(left, "Name", nameField)
        addRow(left, "Speed", speedStepper)
        left.addView(labelRow("Repeat", repeatSegment))
        left.addView(Ds.divider(this))
        addRow(left, "Repeat count", repeatCountStepper)
        addRow(left, "Delay between repeats", delayStepper)
        addRow(left, "Start countdown", countdownStepper)

        val info = Ds.text(this, 11f, Ds.MUTED).apply {
            val lastPlayed = macro.lastPlayedAt?.let {
                SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(it))
            } ?: "never"
            text = "${macro.gestures.size} gestures · ${formatDuration(macro.durationMs)} · " +
                "Played ${macro.playCount} time(s)\nCreated " +
                SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(macro.createdAt)) +
                " · Last played $lastPlayed"
        }
        right.addView(
            Ds.text(this, 11f, Ds.MUTED, caps = true).apply {
                text = "Stop conditions"
                setPadding(0, dp(8), 0, dp(4))
            }
        )
        right.addView(labelRow("Game leaves foreground", stopBackgroundSwitch))
        right.addView(Ds.divider(this))

        val minutesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        minutesRow.addView(
            Ds.text(this, 13f, Ds.MUTED).apply { text = "After minutes" },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        minutesRow.addView(minutesStepper)
        minutesRow.addView(minutesSwitch)
        right.addView(minutesRow)
        right.addView(Ds.divider(this))

        val attacksRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        attacksRow.addView(
            Ds.text(this, 13f, Ds.MUTED).apply { text = "After attacks" },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        attacksRow.addView(attacksStepper)
        attacksRow.addView(attacksSwitch)
        right.addView(attacksRow)
        right.addView(Ds.divider(this))
        right.addView(labelRow("Pin to top", pinSwitch))
        right.addView(Ds.divider(this))
        right.addView(info)
        right.addView(Ds.spacer(this, 4))

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(columns)
        }
        root.addView(header)
        root.addView(Ds.divider(this))
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenHeight * 0.90f - dp(220)).toInt().coerceAtLeast(dp(120))
            )
        )
        root.addView(Ds.divider(this))
        root.addView(error)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        actions.addView(
            LinearLayout(this@FloatingMenuService).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(actionButton("Duplicate") {
                    persist { saved ->
                        scope.launch {
                            val copy = repository.duplicate(saved.id)
                            closeMacroSettings()
                            toast(
                                if (copy != null) "Duplicated as \"${copy.name}\""
                                else "Duplicate failed"
                            )
                        }
                    }
                })
                addView(actionButton("Reset") {
                    showMacroSettings(
                        macro.copy(
                            speedMultiplier = 1f,
                            repeatMode = Macro.REPEAT_ONCE,
                            repeatDelayMs = Macro.LEGACY_REPEAT_DELAY_MS,
                            startCountdownMs = 0L,
                            stopOnBackground = true,
                            stopAfterMinutes = null,
                            stopAfterAttacks = null,
                            pinned = false
                        )
                    )
                })
                addView(actionButton("Delete", danger = true) { deleteMacro(macro) })
            }
        )
        actions.addView(
            LinearLayout(this@FloatingMenuService).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
                addView(actionButton("Cancel") { closeMacroSettings() })
                addView(actionButton("Save") {
                    persist { saved ->
                        closeMacroSettings()
                        BotState.setStatus("Settings saved for \"${saved.name}\"")
                        toast("Saved")
                    }
                })
            }
        )
        root.addView(actions)

        view.post { clampControllerToScreen() }
        repeatCountAlpha?.alpha =
            if (MacroTimeline.isCustomRepeat(draft.repeat)) 1f else 0.45f
    }

    private fun openTextEditor(
        title: String,
        value: String,
        kind: String,
        onValue: (String?) -> Unit
    ) {
        val view = controllerView
        val timeout = Runnable { MacroInputRelay.complete(null) }
        MacroInputRelay.pending = { result ->
            handler.removeCallbacks(timeout)
            view?.visibility = View.VISIBLE
            handler.post { clampControllerToScreen() }
            onValue(result)
        }
        view?.visibility = View.GONE
        handler.postDelayed(timeout, 180_000L)
        try {
            startActivity(
                Intent(this, MacroInputActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MacroInputActivity.EXTRA_TITLE, title)
                    .putExtra(MacroInputActivity.EXTRA_VALUE, value)
                    .putExtra(MacroInputActivity.EXTRA_KIND, kind)
            )
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            MacroInputRelay.complete(null)
            toast("Could not open the text editor")
        }
    }

    private fun closeMacroSettings() {
        val view = controllerView ?: return
        val host = view.findViewById<FrameLayout>(R.id.settingsHost) ?: return
        settingsView = null
        settingsMacroId = -1L
        host.removeAllViews()
        host.visibility = View.GONE
        view.findViewById<View>(R.id.pageTabs).visibility = View.VISIBLE
        view.findViewById<View>(R.id.contentFrame).visibility = View.VISIBLE
        view.post { clampControllerToScreen() }
        refreshUi()
    }

    private fun formatDelaySeconds(ms: Long): String =
        String.format(Locale.US, "%.2f", ms / 1000.0)
            .trimEnd('0')
            .trimEnd('.')

    private fun deleteMacro(macro: Macro) {
        showConfirmDialog(
            title = "Delete macro?",
            message = "\"${macro.name}\" will be removed. You can undo for 5 seconds.",
            positive = "Delete"
        ) {
            scope.launch {
                if (repository.delete(macro.id)) {
                    closeMacroSettings()
                    BotState.setStatus("Deleted \"${macro.name}\"")
                    showUndo(macro)
                } else {
                    toast("Delete failed")
                }
            }
        }
    }

    private fun showUndo(macro: Macro) {
        val view = controllerView ?: return
        pendingDelete = macro
        val undoRow = view.findViewById<View>(R.id.undoRow)
        undoRow.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tvUndoText).text = "Deleted \"${macro.name}\""
        undoTicker?.let(handler::removeCallbacks)
        val ticker = Runnable {
            pendingDelete = null
            undoRow.visibility = View.GONE
        }
        undoTicker = ticker
        handler.postDelayed(ticker, UNDO_WINDOW_MS)
    }

    private fun undoDelete() {
        val macro = pendingDelete ?: return
        val view = controllerView ?: return
        pendingDelete = null
        undoTicker?.let(handler::removeCallbacks)
        undoTicker = null
        view.findViewById<View>(R.id.undoRow).visibility = View.GONE
        scope.launch {
            val restored = repository.save(macro)
            toast(if (restored != null) "Restored \"${macro.name}\"" else "Undo failed")
        }
    }

    private fun nextMacroId(): Long {
        val now = System.currentTimeMillis()
        val highest = macros.maxOfOrNull { it.id } ?: 0L
        return maxOf(now, highest + 1)
    }

    // -------------------------------------------------------------------- ui

    private fun setCaptureOverlayHidden(hidden: Boolean) {
        captureOverlayHidden = hidden
        val view = controllerView ?: return
        if (hidden) {
            view.visibility = View.GONE
        } else {
            view.visibility = View.VISIBLE
            refreshUi()
        }
    }

    private fun refreshUi() {
        val view = controllerView ?: return
        if (captureOverlayHidden) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val state = BotState.state.value
        val count = BotState.recordedGestureCount.value
        if (manualWatch && !resultsCapture.isActive()) {
            manualWatch = false
            captureSession = null
        }
        if (autoWatch && !resultsCapture.isActive()) {
            autoWatch = false
            captureSession = null
        }

        val showRecording = state == ControllerState.RECORDING || state == ControllerState.RECORDING_PAUSED
        val showReview = state == ControllerState.REVIEW
        val showPlaying = state == ControllerState.PLAYING || state == ControllerState.PLAYBACK_PAUSED
        val showReady = !showRecording && !showReview && !showPlaying

        val statsVisible = page == PAGE_STATS
        val startCountdown = BotState.startCountdown.value
        val countdownVisible = startCountdown != null && !statsVisible
        view.findViewById<View>(R.id.statsPanel).visibility = if (statsVisible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.readyPanel).visibility =
            if (!statsVisible && showReady && !countdownVisible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.recordingPanel).visibility =
            if (!statsVisible && showRecording && !countdownVisible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.reviewPanel).visibility =
            if (!statsVisible && showReview && !countdownVisible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.playingPanel).visibility =
            if (!statsVisible && showPlaying && !countdownVisible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.countdownPanel).visibility =
            if (countdownVisible) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.tvStartCountdown).text =
            startCountdown?.toString() ?: ""
        val tabActive = 0xFF3B82F6.toInt()
        val tabIdle = 0xFF1E293B.toInt()
        view.findViewById<Button>(R.id.btnPageController).backgroundTintList =
            ColorStateList.valueOf(if (statsVisible) tabIdle else tabActive)
        view.findViewById<Button>(R.id.btnPageStats).backgroundTintList =
            ColorStateList.valueOf(if (statsVisible) tabActive else tabIdle)

        view.findViewById<Button>(R.id.btnRecord).isEnabled = !startingPassive
        updateScanButton()

        val recordMode = activeRecordingMode ?: settingsStore.settings.value.recordingMode
        val passiveMode = recordMode == RecordingMode.PASSIVE
        view.findViewById<TextView>(R.id.tvRecordMode).apply {
            text = if (passiveMode) "Passive · game responds"
            else "Capture only · blocks game input"
            setTextColor(if (passiveMode) 0xFF22C55E.toInt() else 0xFFF59E0B.toInt())
        }
        view.findViewById<TextView>(R.id.tvRecState).text = when {
            startingPassive -> "STARTING"
            state == ControllerState.RECORDING_PAUSED -> "PAUSED"
            activeRecordingMode == RecordingMode.PASSIVE -> "REC PASSIVE"
            else -> "REC"
        }
        view.findViewById<Button>(R.id.btnRecPause).apply {
            text = if (state == ControllerState.RECORDING_PAUSED) "Resume" else "Pause"
            isEnabled = !startingPassive
        }
        view.findViewById<TextView>(R.id.tvRecTimer).text =
            if (startingPassive) "00:00" else formatClock(elapsedRecordingMs())

        view.findViewById<TextView>(R.id.tvReviewInfo).text =
            "$count gesture(s) · ${formatDuration(MacroTimeline.totalDuration(recorded))}"

        view.findViewById<TextView>(R.id.tvPlayingName).text =
            BotState.activeMacroName.value ?: "Macro"
        view.findViewById<TextView>(R.id.tvPlayingStatus).text = BotState.status.value
        view.findViewById<Button>(R.id.btnPlayPause).text =
            if (state == ControllerState.PLAYBACK_PAUSED) "Resume" else "Pause"
        val activeMacro = macros.firstOrNull { it.id == BotState.activeMacroId.value }
        view.findViewById<TextView>(R.id.tvPlayingSpeed).text =
            "Speed ${MacroTimeline.formatSpeed(activeMacro?.speedMultiplier ?: 1f)}"
        view.findViewById<TextView>(R.id.tvPlayingRepeat).text =
            MacroTimeline.repeatLabel(activeMacro?.repeatMode)

        val countdown = BotState.repeatCountdown.value
        val bubbleStatus = view.findViewById<TextView>(R.id.bubbleStatus)
        bubbleStatus.text = when {
            manualWatch -> "WATCH"
            state == ControllerState.RECORDING -> "REC"
            state == ControllerState.RECORDING_PAUSED -> "PAUSED"
            state == ControllerState.PLAYBACK_PAUSED -> "II"
            state == ControllerState.PLAYING && startCountdown != null -> "${startCountdown}s"
            state == ControllerState.PLAYING && countdown != null -> "${countdown}s"
            state == ControllerState.PLAYING -> "PLAY"
            else -> ""
        }

        if (collapsed) {
            view.findViewById<View>(R.id.panel).visibility = View.GONE
            view.findViewById<View>(R.id.bubble).visibility = View.VISIBLE
        }
    }

    private fun updateRecordingInfo() {
        val view = controllerView ?: return
        val count = BotState.recordedGestureCount.value
        view.findViewById<TextView>(R.id.tvReviewInfo).text =
            "$count gesture(s) · ${formatDuration(MacroTimeline.totalDuration(recorded))}"
        view.findViewById<TextView>(R.id.tvRecTimer).text =
            if (startingPassive) "00:00" else formatClock(elapsedRecordingMs())
    }

    private fun onStatusChanged(status: String) {
        val view = controllerView ?: return
        view.findViewById<TextView>(R.id.tvStatus).text = status
        view.findViewById<TextView>(R.id.tvPlayingStatus).text = status
        if (manualWatch && !resultsCapture.isActive()) {
            manualWatch = false
            captureSession = null
        }
        if (autoWatch && !resultsCapture.isActive()) {
            autoWatch = false
            captureSession = null
        }
        refreshUi()
    }

    private fun updateRecordingNotification() {
        val state = BotState.state.value
        val count = BotState.recordedGestureCount.value
        when (state) {
            ControllerState.RECORDING ->
                updateNotification(
                    getString(
                        if (activeRecordingMode == RecordingMode.PASSIVE) {
                            R.string.notification_recording_passive
                        } else {
                            R.string.notification_recording_capture
                        },
                        count
                    )
                )

            ControllerState.RECORDING_PAUSED ->
                updateNotification("Recording paused")

            else -> Unit
        }
    }

    // --------------------------------------------------------------- dialogs

    private fun requestClose() {
        val state = BotState.state.value
        AppEventLog.log("requestClose state=$state settingsOpen=${settingsView != null}")
        val unsaved = state == ControllerState.RECORDING ||
            state == ControllerState.RECORDING_PAUSED ||
            (state == ControllerState.REVIEW && recorded.isNotEmpty())
        if (!unsaved) {
            closeEverything()
            return
        }
        showConfirmDialog(
            title = "Unsaved recording",
            message = "Close the controller and lose the current recording?",
            positive = "Discard and close"
        ) { closeEverything() }
    }

    private fun showInputDialog(
        title: String,
        initial: String,
        onFailure: (() -> Unit)? = null,
        onConfirm: (String) -> Unit
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_macro_name, null)
        val dialog = Dialog(this).apply {
            setContentView(view)
            window?.setType(overlayType())
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT)
            window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
            setCanceledOnTouchOutside(false)
        }
        view.findViewById<TextView>(R.id.tvDialogTitle).text = title
        val input = view.findViewById<EditText>(R.id.etDialogName)
        val save = view.findViewById<Button>(R.id.btnDialogSave)
        val counter = view.findViewById<TextView>(R.id.tvDialogCount)
        input.setText(initial)
        input.setSelection(input.text.length)
        val refresh = {
            val length = input.text.length
            counter.text = "$length/40"
            save.isEnabled = input.text.toString().isNotBlank()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        save.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) {
                dialog.dismiss()
                onConfirm(value)
            }
        }
        view.findViewById<Button>(R.id.btnDialogCancel).setOnClickListener {
            dialog.dismiss()
        }
        refresh()
        try {
            dialog.show()
            input.requestFocus()
        } catch (e: Exception) {
            Log.e(TAG, "Input dialog failed", e)
            if (onFailure != null) {
                onFailure.invoke()
            } else {
                toast("Could not open dialog")
            }
        }
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        positive: String,
        onConfirm: () -> Unit
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
        val dialog = Dialog(this).apply {
            setContentView(view)
            window?.setType(overlayType())
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(dp(300), WindowManager.LayoutParams.WRAP_CONTENT)
            setCanceledOnTouchOutside(true)
        }
        view.findViewById<TextView>(R.id.tvConfirmTitle).text = title
        view.findViewById<TextView>(R.id.tvConfirmMessage).text = message
        view.findViewById<Button>(R.id.btnConfirmOk).apply {
            text = positive
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        view.findViewById<Button>(R.id.btnConfirmCancel).setOnClickListener {
            dialog.dismiss()
        }
        try {
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Confirm dialog failed", e)
            toast("Could not open dialog")
        }
    }

    // ---------------------------------------------------------- notification

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_desc) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val closeIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingMenuService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.action_stop), closeIntent)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        if (notificationStarted) {
            updateNotification(text)
            return
        }
        val notification = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            notificationStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun updateNotification(text: String) {
        if (!notificationStarted) return
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
            // Notification is best effort.
        }
    }

    // --------------------------------------------------------------- helpers

    private fun closeEverything() {
        AppEventLog.log("closeEverything state=${BotState.state.value}")
        GameSessionTracker.closeSessionAsync()
        stopPlayback()
        stopRecording(silent = true)
        stopPassiveRecorder()
        removeIndicator()
        removeCapture()
        removeController()
        BotState.clearActiveMacro()
        BotState.clearRecording()
        BotState.setState(ControllerState.INACTIVE)
        BotState.setStatus("Controller closed")
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // Already stopped.
        }
        stopSelf()
    }

    private fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    private fun currentOrientation(): String =
        if (screenWidth > screenHeight) "landscape" else "portrait"

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60
        return String.format(Locale.US, "%dm %02ds", minutes, seconds)
    }

    private fun formatClock(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun haptic() {
        if (!settingsStore.settings.value.hapticFeedback) return
        try {
            controllerView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {
            // Haptics are best effort.
        }
    }

    private fun toast(message: String) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // Toast can fail if the app is going away.
        }
    }

    companion object {
        private const val PAGE_CONTROLLER = 0
        private const val PAGE_STATS = 1
        private const val STATS_SESSION = 0
        private const val STATS_ALL = 1
        private const val TAG = "AutoClashController"
        private const val AUTO_RESTART_DELAY_MS = 5_000L
        private const val CHANNEL_ID = "autoclash_controller"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_THROTTLE_MS = 900L
        private const val STATUS_THROTTLE_MS = 500L
        private const val UNDO_WINDOW_MS = 5_000L
        private const val BUBBLE_SIZE_DP = 48
        private const val BUBBLE_ICON_DP = 34
        private const val BUBBLE_MARGIN_DP = 8
        private const val BUBBLE_TOP_DP = 48
        private const val BUBBLE_TOP_LANDSCAPE_DP = 8
        private const val EXTRA_MACRO_ID = "macro_id"

        const val ACTION_PLAY = "com.clahbot.action.PLAY"
        const val ACTION_CLOSE = "com.clahbot.action.CLOSE"
        const val ACTION_RESET_POSITION = "com.clahbot.action.RESET_POSITION"

        fun show(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingMenuService::class.java)
            )
        }

        fun play(context: Context, macroId: Long) {
            val intent = Intent(context, FloatingMenuService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_MACRO_ID, macroId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun resetPosition(context: Context) {
            context.startService(
                Intent(context, FloatingMenuService::class.java).setAction(ACTION_RESET_POSITION)
            )
        }

        fun close(context: Context) {
            context.startService(
                Intent(context, FloatingMenuService::class.java).setAction(ACTION_CLOSE)
            )
        }
    }
}
