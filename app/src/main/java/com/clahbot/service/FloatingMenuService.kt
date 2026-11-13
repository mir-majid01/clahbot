package com.clahbot.service

import android.annotation.SuppressLint
import android.animation.ValueAnimator
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
import android.util.TypedValue
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.clahbot.R
import android.content.res.ColorStateList
import com.clahbot.data.AppEventLog
import com.clahbot.data.AttackRepository
import com.clahbot.data.BotState
import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.AttackStats
import com.clahbot.data.ControllerState
import com.clahbot.data.MacroNaming
import com.clahbot.data.MacroRepository
import com.clahbot.data.MacroSelection
import com.clahbot.data.MacroWriteResult
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
import com.clahbot.data.models.MacroSettingsDraft
import com.clahbot.data.models.RecordedGesture
import com.clahbot.execution.MacroPlayer
import com.clahbot.execution.MacroTimeline
import com.clahbot.execution.StopSummary
import com.clahbot.overlay.BubbleCorner
import com.clahbot.overlay.OverlayBounds
import com.clahbot.overlay.OverlayPlacement
import com.clahbot.overlay.OverlayPoint
import com.clahbot.overlay.OverlayPositionStore
import com.clahbot.overlay.TouchTargets
import com.clahbot.overlay.TouchEffectsView
import com.clahbot.walls.AutoWallsDevice
import com.clahbot.walls.AutoWallsRepository
import com.clahbot.walls.AutoWallsRuntime
import com.clahbot.walls.AutoWallsScan
import com.clahbot.walls.AutoWallsScanner
import com.clahbot.walls.AutoWallsScanReport
import com.clahbot.walls.AutoWallsSession
import com.clahbot.walls.AutoWallsState
import com.clahbot.walls.WallCurrency
import com.clahbot.input.BuiltGesture
import com.clahbot.input.GetEventDiscovery
import com.clahbot.input.InputAccessManager
import com.clahbot.input.InputEventSource
import com.clahbot.input.PassiveRecorder
import com.clahbot.input.ScreenGeometry
import com.clahbot.input.ScreenRect
import com.clahbot.input.TouchscreenDevice
import com.clahbot.results.AppResultsReader
import com.clahbot.results.CaptureSession
import com.clahbot.results.ResultCaptureGate
import com.clahbot.results.ResultsCaptureCoordinator
import com.clahbot.game.GameScreenEngine
import com.clahbot.game.GameState
import com.clahbot.recovery.RecoveryAgent
import com.clahbot.recovery.RecoveryRepositoryHolder
import com.clahbot.recovery.RecoveryWatchdog
import com.clahbot.data.RecoveryStripUi
import com.clahbot.data.ResultToastUi
import com.clahbot.session.GameSessionTracker
import com.clahbot.ui.MacroInputActivity
import com.clahbot.ui.MacroInputRelay
import com.clahbot.ui.MainActivity
import com.clahbot.ui.SettingsActivity
import com.clahbot.ui.design.Ds
import com.clahbot.ui.widget.LootRowLayout
import com.clahbot.util.Haptics
import com.clahbot.util.PermissionHelper
import com.clahbot.util.Plural
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlin.math.hypot
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

    private enum class PlacementKind(val key: String) {
        CONTROLLER("controller"),
        BUBBLE("bubble"),
        RECORDING("recording"),
        PLAYING("playing")
    }

    private lateinit var windowManager: WindowManager
    private lateinit var repository: MacroRepository
    private lateinit var settingsStore: SettingsStore
    private lateinit var attackRepository: AttackRepository
    private lateinit var sessionRepository: SessionRepository
    private var statsScope = STATS_SESSION
    private var autoScanChip: TextView? = null
    private var scanNowLink: Button? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    /** Context with the controller size applied; every overlay dp/sp uses it. */
    private var uiContext: Context = this

    private var controllerView: View? = null
    private var controllerParams: WindowManager.LayoutParams? = null
    private var overlayPositions: OverlayPositionStore? = null
    private var snapAnimator: ValueAnimator? = null
    private var collapseAnimator: ValueAnimator? = null
    private var dragFrameCallback: Choreographer.FrameCallback? = null
    private var pendingDragPoint: OverlayPoint? = null
    private var pendingDragKind: PlacementKind? = null
    private val touchTargetsRunnable = Runnable { installTouchTargetsNow() }
    private var lastBubbleCorner: BubbleCorner? = null
    private var lastBubbleScale: Float = 1f
    private var lastAutoDock: Boolean = true
    private var recordingMinimized = false
    private var lastRecordingMinimized = false
    private var recordingChromeActive = false
    private var reviewChromeActive = false
    private var playbackChromeActive = false
    private var playbackMinimized = false
    private var pendingPlaybackPoint: OverlayPoint? = null
    private var playbackRunActive = false
    private var pendingAutoOpen = false
    private var autoOpenTimeout: Runnable? = null
    private var autoOpenAnimator: ValueAnimator? = null
    private var serviceDestroying = false
    private var captureView: View? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var effectsView: TouchEffectsView? = null
    private var passiveRecorder: PassiveRecorder? = null
    private var startingPassive = false
    private var preparingPassive = false
    private var prepareJob: Job? = null
    private var prepareSource: InputEventSource? = null
    private var prepareToken = 0
    private var tappingSwitchUpdating = false
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
    private var settingsDraft: MacroSettingsDraft? = null
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
    private var lastPulseCount: Int = -1
    private var reviewName: String = ""
    private var savePending = false
    private var saveJob: Job? = null
    private var savedMacro: Macro? = null
    private var discardedGestures: List<RecordedGesture>? = null
    private var saveUndoRunnable: Runnable? = null
    private var pendingRecAction: RecConfirmAction? = null
    private var confirmRevert: Runnable? = null

    private enum class RecConfirmAction { RESTART, CANCEL }

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

    private val gameEngine = GameScreenEngine()
    private var recoveryAgent: RecoveryAgent? = null
    private var recoveryWatchdog: RecoveryWatchdog? = null
    /** Auto Walls 2.0 runtime cached for the attack counter feed; runs start fresh. */
    private var autoWallsRuntime: AutoWallsRuntime? = null
    private var autoWallsScanJob: Job? = null
    private var autoWallsRunJob: Job? = null

    private val autoWallsRepository: AutoWallsRepository
        get() = AutoWallsRepository.get(this)
    private var resultToastHide: Runnable? = null

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!recording) return
            val timer = formatClock(elapsedRecordingMs())
            controllerView?.findViewById<TextView>(R.id.tvRecTimer)?.text = timer
            controllerView?.findViewById<TextView>(R.id.tvRecPillTimer)?.text = timer
            updateTouchCounter(animate = false)
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        GameSessionTracker.start(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repository = MacroRepository.get(this)
        settingsStore = SettingsStore.get(this)
        overlayPositions = OverlayPositionStore.get(this)
        lastBubbleCorner = settingsStore.settings.value.bubbleCorner
        lastBubbleScale = settingsStore.settings.value.bubbleScale
        lastAutoDock = settingsStore.settings.value.autoDockIcon
        attackRepository = AttackRepository.get(this)
        sessionRepository = SessionRepository.get(this)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        AppEventLog.init(this, versionName)
        resultsReader = AppResultsReader(this)
        RecoveryRepositoryHolder.init(this)
        recoveryAgent = RecoveryAgent(gameEngine, resultsReader)
        ClahAccessibilityService.overlayMaskProvider = {
            controllerBoundsSnapshot?.let { bounds ->
                listOf(
                    Rect(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt()
                    )
                )
            } ?: emptyList()
        }
        resultsCapture = ResultsCaptureCoordinator(
            context = this,
            scope = scope,
            attackRepository = attackRepository,
            settingsStore = settingsStore,
            reader = resultsReader,
            gate = resultGate,
            onStatus = { message -> BotState.setStatus(message) },
            onEpisodeActive = { active -> effectsView?.suppressed = active },
            onRecordSaved = { record ->
                if (playbackRunActive || BotState.state.value.isPlayback) {
                    BotState.noteRunAttack()
                }
                AppEventLog.log(
                    "record callback playerRunning=${player?.isRunning() == true} " +
                        "runAttacks=${BotState.runAttackCount.value} " +
                        "repeat=${captureSession?.repeat ?: "-"} captureId=${captureSession?.id ?: "-"} " +
                        "manual=$manualWatch"
                )
                scope.launch {
                    attackRepository.refresh()
                    GameSessionTracker.ensureSession(record.startedAt)
                    GameSessionTracker.syncNow(includeIds = setOf(record.id))
                    noteAutoWallsAttack()
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
                if (pendingAutoOpen) maybeAutoOpen()
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
                if ((state == ControllerState.ERROR || state == ControllerState.READY) &&
                    playbackRunActive
                ) {
                    endAttackSession(interrupted = false)
                    onPlaybackRunEnded()
                }
                refreshUi()
            }
        }
        scope.launch { BotState.status.collect { onStatusChanged(it) } }
        scope.launch {
            BotState.startCountdown.collect {
                refreshUi()
            }
        }
        scope.launch { BotState.recoveryStrip.collect { refreshUi() } }
        scope.launch { BotState.resultToast.collect { applyResultToast(it) } }
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
                val previousCorner = lastBubbleCorner
                if (previousCorner != null && previousCorner != settings.bubbleCorner) {
                    overlayPositions?.clear("bubble")
                    if (collapsed && !recording && !startingPassive) {
                        val size = currentBubbleSizePx()
                        val point = OverlayPlacement.defaultBubble(
                            settings.bubbleCorner,
                            size,
                            size,
                            safeBounds(),
                            dp(8)
                        )
                        applyWindowPoint(params, point, PlacementKind.BUBBLE)
                    }
                }
                effectsView?.setRecordingConfig(settings.recordingEffect)
                effectsView?.setPlayingConfig(settings.playingEffect)
                lastBubbleCorner = settings.bubbleCorner
                if (lastAutoDock != settings.autoDockIcon) {
                    lastAutoDock = settings.autoDockIcon
                    if (collapsed && !recording && !startingPassive) {
                        val size = currentBubbleSizePx()
                        val point = if (settings.autoDockIcon) {
                            defaultBubblePoint()
                        } else {
                            savedPosition(PlacementKind.BUBBLE)
                                ?: bubblePointFromLogo(view, size)
                        }
                        applyWindowPoint(
                            params,
                            OverlayPlacement.clamp(
                                point.x, point.y, size, size, safeBounds(), dp(8)
                            ),
                            PlacementKind.BUBBLE,
                            save = false
                        )
                    }
                }
                if (lastBubbleScale != settings.bubbleScale) {
                    lastBubbleScale = settings.bubbleScale
                    applyBubbleSize(view, uiContext, settings.bubbleScale)
                    lastBubbleRing = Int.MIN_VALUE
                    if (collapsed && !recording && !startingPassive) {
                        val size = currentBubbleSizePx()
                        val point = if (settings.autoDockIcon) {
                            defaultBubblePoint()
                        } else {
                            savedPosition(PlacementKind.BUBBLE)
                                ?: bubblePointFromLogo(view, size)
                        }
                        applyWindowPoint(
                            params,
                            OverlayPlacement.clamp(
                                point.x,
                                point.y,
                                size,
                                size,
                                safeBounds(),
                                dp(8)
                            ),
                            PlacementKind.BUBBLE,
                            save = false
                        )
                    }
                    refreshUi()
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
            ACTION_OPEN -> {
                ensureController()
                expandController()
                cancelAutoOpenNotification()
            }
            ACTION_CLOSE -> requestClose()
            else -> ensureController()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelAutoOpen()
        if (recording) {
            updateScreenSize()
            stopRecording()
            BotState.setStatus("Screen changed — recording stopped")
            toast("Recording stopped: screen changed")
            return
        }
        val previousParams = controllerParams
        if (previousParams != null && settingsView == null && !startingPassive) {
            savePosition(
                if (collapsed) PlacementKind.BUBBLE else PlacementKind.CONTROLLER,
                previousParams
            )
        }
        updateScreenSize()
        applyPanelWidth(settingsView != null)
        refreshControllerBoundsSnapshot()
        val openDraft = settingsDraft
        val openPage = settingsPage
        if (settingsView != null) {
            val macro = macros.firstOrNull { it.id == settingsMacroId }
            if (macro != null) showMacroSettings(macro, openDraft, openPage) else closeMacroSettings()
        } else if (page == PAGE_STATS) {
            renderStats()
        } else {
            renderMacroList()
        }
        if (settingsView == null && !recording && !startingPassive) {
            val view = controllerView
            val params = controllerParams
            if (view != null && params != null) {
                if (collapsed) {
                    val point = savedPosition(PlacementKind.BUBBLE) ?: defaultBubblePoint()
                    val size = currentBubbleSizePx()
                    applyWindowPoint(
                        params,
                        OverlayPlacement.clamp(
                            point.x,
                            point.y,
                            size,
                            size,
                            safeBounds(),
                            dp(8)
                        ),
                        PlacementKind.BUBBLE
                    )
                } else {
                    applyPanelWidth(settings = false)
                    val panel = view.findViewById<View>(R.id.panel)
                    val panelWidth = panel?.width?.takeIf { it > 0 }
                        ?: controllerPanelWidthPx()
                    val panelHeight = panel?.height?.takeIf { it > 0 }
                        ?: controllerPanelHeightPx()
                    val point = savedPosition(PlacementKind.CONTROLLER)
                        ?: defaultControllerPoint(panelWidth, panelHeight)
                    applyWindowPoint(
                        params,
                        OverlayPlacement.clamp(
                            point.x,
                            point.y,
                            panelWidth,
                            panelHeight,
                            safeBounds(),
                            dp(8)
                        ),
                        PlacementKind.CONTROLLER
                    )
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceDestroying = true
        ClahAccessibilityService.overlayMaskProvider = { emptyList() }
        cancelAutoOpen()
        prepareToken++
        prepareJob?.cancel()
        prepareJob = null
        preparingPassive = false
        prepareSource?.let { source ->
            scope.launch(Dispatchers.IO) { source.close() }
        }
        prepareSource = null
        stopPlaybackTicker()
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
        removeCapture()
        removeEffectsOverlay()
        removeController()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ macro sync

    private fun onMacrosChanged(list: List<Macro>) {
        macros = list.filter { it.kind != Macro.KIND_WALL }
        // Adopt a selection made on the dashboard, then validate it against the
        // library so a deleted macro can never stay selected.
        val desired = if (selectedId > 0) selectedId else BotState.selectedMacroId.value
        val reconciled = MacroSelection.reconcile(desired, macros)
        if (reconciled != selectedId) {
            selectedId = reconciled
            BotState.setSelectedMacroId(reconciled)
        }
        if (settingsMacroId > 0 && list.none { it.id == settingsMacroId }) {
            closeMacroSettings()
        }

        val activeId = BotState.activeMacroId.value
        if (activeId > 0 && macros.none { it.id == activeId }) {
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
            syncMacroLists()
            renderMacroList()
            expandController()
            return
        }

        updateScreenSize()
        val prefs = settingsStore.settings.value
        val controllerContext = scaledControllerContext(prefs.controllerScale)
        uiContext = controllerContext
        val view = LayoutInflater.from(controllerContext)
            .inflate(R.layout.overlay_controller, null)
        view.findViewById<LinearLayout>(R.id.panel)?.let { panel ->
            panel.layoutParams = panel.layoutParams.apply {
                width = controllerPanelWidthPx()
                height = controllerPanelHeightPx()
            }
        }
        view.findViewById<ScrollView>(R.id.macroScroll)?.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(2))
        }
        applyBubbleSize(view, controllerContext, prefs.bubbleScale)
        val initialPosition = overlayPositions?.get(
            "controller_${currentOrientation()}"
        ) ?: defaultControllerPoint(controllerPanelWidthPx(), controllerPanelHeightPx())
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialPosition.x
            y = initialPosition.y
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
        ensureEffectsOverlay()
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
        syncMacroLists()
        renderMacroList()
        scope.launch { attackRepository.refresh() }
    }

    private fun ensureEffectsOverlay() {
        if (effectsView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val view = TouchEffectsView(this)
        val prefs = settingsStore.settings.value
        view.setRecordingConfig(prefs.recordingEffect)
        view.setPlayingConfig(prefs.playingEffect)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            windowManager.addView(view, params)
            effectsView = view
        } catch (_: Exception) {
            // Overlay not available; effects are optional.
        }
    }

    private fun removeEffectsOverlay() {
        val view = effectsView ?: return
        effectsView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Already detached.
        }
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
        listOf(R.id.logoFrame, R.id.bubbleLogoFrame).forEach { id ->
            view.findViewById<View>(id)?.apply {
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(source: View, outline: android.graphics.Outline) {
                        val radius = minOf(source.width, source.height) * 0.30f
                        outline.setRoundRect(0, 0, source.width, source.height, radius)
                    }
                }
            }
        }

        view.findViewById<View>(R.id.controllerHeader)
            .setOnTouchListener(dragListener(params, PlacementKind.CONTROLLER, onTap = {}))
        view.findViewById<View>(R.id.bubble)
            .setOnTouchListener(dragListener(params, PlacementKind.BUBBLE, onTap = { expandController() }))

        view.findViewById<ImageButton>(R.id.btnMinimize).setOnClickListener {
            haptic()
            collapseController()
        }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { requestClose() }
        view.findViewById<Button>(R.id.btnRecord).setOnClickListener {
            haptic()
            startRecording()
        }
        view.findViewById<TextView>(R.id.btnPageController).setOnClickListener { setPage(PAGE_CONTROLLER) }
        view.findViewById<TextView>(R.id.btnPageStats).setOnClickListener { setPage(PAGE_STATS) }
        view.findViewById<TextView>(R.id.btnPageWalls).setOnClickListener { setPage(PAGE_WALLS) }
        view.findViewById<Button>(R.id.btnUndo).setOnClickListener {
            haptic()
            undoDelete()
        }
        scanNowLink = view.findViewById<Button>(R.id.btnScanNow).apply {
            setOnClickListener {
                haptic()
                if (manualWatch) stopManualWatch() else startManualWatch()
            }
        }
        view.findViewById<Button>(R.id.btnSeeHistory).setOnClickListener {
            haptic()
            showOpenAppConfirm(
                "Open AutoClash?",
                "This leaves Clash of Clans. Your game stays open in the background.",
                "Open app"
            ) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_OPEN_HISTORY, true)
                )
            }
        }

        view.findViewById<ImageButton>(R.id.btnRecPause).setOnClickListener {
            haptic()
            toggleRecordingPause()
        }
        view.findViewById<View>(R.id.btnRecFinish).setOnClickListener {
            haptic()
            stopRecording()
        }
        view.findViewById<ImageButton>(R.id.btnRecMinimize).setOnClickListener {
            haptic()
            minimizeRecordingBar()
        }
        view.findViewById<ImageButton>(R.id.btnRecUndo).setOnClickListener {
            undoLastRecordedAction()
        }
        view.findViewById<ImageButton>(R.id.btnRecRestart).setOnClickListener {
            haptic()
            requestRecordingRestart()
        }
        view.findViewById<ImageButton>(R.id.btnRecCancel).setOnClickListener {
            haptic()
            requestRecordingCancel()
        }
        view.findViewById<Button>(R.id.btnRecKeep).setOnClickListener {
            haptic()
            dismissRecConfirm()
        }
        view.findViewById<Button>(R.id.btnRecDiscard).setOnClickListener {
            haptic()
            confirmRecAction()
        }
        view.findViewById<View>(R.id.recDragBar)
            .setOnTouchListener(dragListener(params, PlacementKind.RECORDING, onTap = {}))
        view.findViewById<View>(R.id.recPill)
            .setOnTouchListener(
                dragListener(params, PlacementKind.RECORDING, onTap = { expandRecordingBar() })
            )

        view.findViewById<View>(R.id.btnSaveNameSave).setOnClickListener {
            haptic()
            saveRecording()
        }
        view.findViewById<View>(R.id.btnSaveNameDiscard).setOnClickListener {
            haptic()
            discardReviewedRecording()
        }
        view.findViewById<View>(R.id.btnSavedUndo).setOnClickListener {
            haptic()
            undoSavedMacro()
        }
        view.findViewById<View>(R.id.btnDiscardedUndo).setOnClickListener {
            haptic()
            undoDiscardedRecording()
        }
        view.findViewById<EditText>(R.id.etMacroName)?.apply {
            setOnClickListener { setSaveTyping(true) }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) setSaveTyping(true)
                false
            }
            setOnFocusChangeListener { _, focused ->
                if (focused) setSaveTyping(true) else setSaveTyping(false)
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN)
                ) {
                    saveRecording()
                    true
                } else {
                    false
                }
            }
        }
        view.findViewById<View>(R.id.playDragBar)
            .setOnTouchListener(dragListener(params, PlacementKind.PLAYING, onTap = {}))
        view.findViewById<View>(R.id.playPill)
            .setOnTouchListener(
                dragListener(params, PlacementKind.PLAYING, onTap = { expandPlaybackStrip() })
            )
        view.findViewById<ImageButton>(R.id.btnPlayMinimize).setOnClickListener {
            haptic()
            minimizePlaybackStrip()
        }

        view.findViewById<ImageButton>(R.id.btnPlayPause).setOnClickListener {
            haptic()
            togglePlaybackPause()
        }
        view.findViewById<ImageButton>(R.id.btnStopPlayback).setOnClickListener {
            haptic()
            stopPlayback()
            BotState.setState(ControllerState.READY)
            BotState.setStatus("Playback stopped")
        }

        view.findViewById<View>(R.id.recoveryDragBar)
            .setOnTouchListener(dragListener(params, PlacementKind.PLAYING, onTap = {}))
        view.findViewById<ImageButton>(R.id.btnRecoveryStop).setOnClickListener {
            haptic()
            stopPlayback()
            BotState.setState(ControllerState.READY)
            BotState.setStatus("Playback stopped")
        }

        bindAutoWallsPanel(view)
    }

    private fun bindAutoWallsPanel(view: View) {
        view.findViewById<Switch>(R.id.wallSwitch).setOnCheckedChangeListener { _, checked ->
            if (!checked) hideTappingConfirm(view)
            val config = autoWallsRepository.loadConfig()
            autoWallsRepository.saveConfig(config.copy(enabled = checked))
            refreshAutoWalls()
        }
        listOf(
            R.id.btnWallModeGold to WallCurrency.GOLD,
            R.id.btnWallModeElixir to WallCurrency.ELIXIR,
            R.id.btnWallModeBoth to WallCurrency.BOTH
        ).forEach { (id, currency) ->
            view.findViewById<TextView>(id).setOnClickListener {
                haptic()
                val config = autoWallsRepository.loadConfig()
                autoWallsRepository.saveConfig(
                    config.copy(selection = config.selection.copy(currency = currency))
                )
                refreshAutoWalls()
            }
        }
        view.findViewById<Switch>(R.id.wallTappingSwitch)
            .setOnCheckedChangeListener { button, checked ->
                if (tappingSwitchUpdating) return@setOnCheckedChangeListener
                if (checked) {
                    // Two-step confirm: the switch only turns on after the user
                    // acknowledges that the bot will start touching Clash.
                    button.isChecked = false
                    showTappingConfirm(view)
                } else {
                    hideTappingConfirm(view)
                    val config = autoWallsRepository.loadConfig()
                    autoWallsRepository.saveConfig(config.copy(allowTapping = false))
                    refreshAutoWalls()
                }
            }
        view.findViewById<View>(R.id.btnTappingYes).setOnClickListener {
            haptic()
            hideTappingConfirm(view)
            val config = autoWallsRepository.loadConfig()
            autoWallsRepository.saveConfig(config.copy(allowTapping = true))
            tappingSwitchUpdating = true
            view.findViewById<Switch>(R.id.wallTappingSwitch).isChecked = true
            tappingSwitchUpdating = false
            AppEventLog.log("auto-walls: tapping enabled by the user")
            refreshAutoWalls()
        }
        view.findViewById<View>(R.id.btnTappingNo).setOnClickListener {
            haptic()
            hideTappingConfirm(view)
        }
        view.findViewById<View>(R.id.btnWallScan).setOnClickListener {
            haptic()
            startAutoWallsScan()
        }
        view.findViewById<View>(R.id.btnWallRun).setOnClickListener {
            haptic()
            startAutoWallsRun()
        }
    }

    private fun showTappingConfirm(view: View) {
        val box = view.findViewById<View>(R.id.wallTappingConfirm) ?: return
        box.background = Ds.rounded(Ds.WARN_BG, 8, this)
        box.visibility = View.VISIBLE
    }

    private fun hideTappingConfirm(view: View) {
        view.findViewById<View>(R.id.wallTappingConfirm)?.visibility = View.GONE
    }

    private fun refreshAutoWalls() {
        val view = controllerView ?: return
        val config = autoWallsRepository.loadConfig()
        tappingSwitchUpdating = true
        view.findViewById<Switch>(R.id.wallSwitch)?.let {
            if (it.isChecked != config.enabled) it.isChecked = config.enabled
        }
        view.findViewById<Switch>(R.id.wallTappingSwitch)?.let {
            if (it.isChecked != config.allowTapping) it.isChecked = config.allowTapping
        }
        tappingSwitchUpdating = false
        if (!config.allowTapping) hideTappingConfirm(view)
        listOf(
            R.id.btnWallModeGold to WallCurrency.GOLD,
            R.id.btnWallModeElixir to WallCurrency.ELIXIR,
            R.id.btnWallModeBoth to WallCurrency.BOTH
        ).forEach { (id, currency) ->
            view.findViewById<TextView>(id)?.apply {
                background = Ds.rounded(
                    if (currency == config.selection.currency) Ds.ACCENT else Ds.BUTTON,
                    Ds.RADIUS_BUTTON,
                    this@FloatingMenuService
                )
                setTextColor(
                    if (currency == config.selection.currency) {
                        android.graphics.Color.WHITE
                    } else {
                        Ds.MUTED
                    }
                )
            }
        }
        view.findViewById<TextView>(R.id.tvWallStatus)?.text = when {
            config.enabled && config.allowTapping -> "On · tapping enabled"
            config.enabled -> "On · scan only (tapping off)"
            else -> "Off — turn on to let Auto Walls run"
        }
        view.findViewById<TextView>(R.id.tvWallReport)?.text =
            scanReportText(autoWallsRepository.loadScan())
    }

    private fun scanReportText(report: AutoWallsScanReport?): String {
        if (report == null) {
            return "No scan yet — open the wall list in Clash, then tap Scan screen."
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(report.at))
        val head = time + " · " + (report.foregroundPackage ?: "foreground unreadable")
        if (!report.captured) return head + "\n" + (report.reason ?: "screen not captured")
        val picked = report.selected?.let {
            "would pick L${it.level} ${it.currency} ${AutoWallsScan.priceLabel(it.price)}"
        } ?: "nothing eligible"
        val body = "${report.candidates.size} row(s) · ${report.eligible.size} eligible · $picked"
        val balances = buildList {
            report.goldBalance?.let {
                add("Gold ${AutoWallsScan.priceLabel(it)} → ${report.affordableGold} walls")
            }
            report.elixirBalance?.let {
                add("Elixir ${AutoWallsScan.priceLabel(it)} → ${report.affordableElixir} walls")
            }
        }.joinToString(" · ")
        return head + "\n" + body +
            (if (balances.isEmpty()) "" else "\n$balances") +
            (report.reason?.let { "\n$it" } ?: "")
    }

    /** One read-only scan from the controller: Clash is already on screen, so no countdown. */
    private fun startAutoWallsScan() {
        if (autoWallsScanJob?.isActive == true) {
            toast("A scan is already running")
            return
        }
        hapticEvent()
        toast("Scanning the wall list…")
        autoWallsScanJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    AutoWallsScanner(
                        repository = autoWallsRepository,
                        device = AutoWallsDevice(gameEngine, resultsReader),
                        log = { message -> AppEventLog.log(message) }
                    ).scan()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AutoWallsScan.captureFailed(
                        foregroundPackage = null,
                        config = autoWallsRepository.loadConfig(),
                        detail = "scan failed: ${e.message ?: e.javaClass.simpleName}"
                    )
                }
            }
            refreshAutoWalls()
            toast(
                result.selected?.let { "Scan done — would pick L${it.level}" }
                    ?: (result.reason ?: "Scan done")
            )
        }
    }

    /**
     * A manual run of the Auto Walls 2.0 loop. The attack playback is parked
     * through the same gate the taught wait screens use, the machine runs, and
     * afterwards the attack resumes — or restarts if nothing was playing.
     */
    private fun startAutoWallsRun() {
        if (autoWallsRunJob?.isActive == true) {
            toast("A wall run is already going")
            return
        }
        val config = autoWallsRepository.loadConfig()
        if (!config.enabled) {
            toast("Turn Auto Walls on first")
            return
        }
        hapticEvent()
        toast("Auto Walls run starting…")
        autoWallsRunJob = scope.launch {
            val hadPlayback = player?.isRunning() == true
            player?.setGateHeld(true)
            var result: AutoWallsRuntime.Outcome? = null
            try {
                result = withContext(Dispatchers.Default) {
                    val session = AutoWallsSession(autoWallsRepository)
                    session.start(allowTapping = config.allowTapping)
                    AutoWallsRuntime(
                        session = session,
                        device = AutoWallsDevice(gameEngine, resultsReader),
                        log = { message -> AppEventLog.log(message) }
                    ).run(manual = true)
                }
                val outcome = result
                AppEventLog.log(
                    "auto-walls run: ${outcome.state} ${outcome.reason} " +
                        "verified=${outcome.verifiedUpgrades}"
                )
                BotState.setStatus(
                    if (outcome.verifiedUpgrades > 0) {
                        "Auto Walls: ${outcome.verifiedUpgrades} upgrade(s) verified"
                    } else {
                        "Auto Walls: ${outcome.reason}"
                    }
                )
            } finally {
                withContext(NonCancellable) {
                    player?.setGateHeld(false)
                    val outcome = result
                    val finished = when {
                        outcome == null -> false
                        !config.allowTapping -> false
                        else -> outcome.verifiedUpgrades > 0 ||
                            outcome.state == AutoWallsState.COOLDOWN
                    }
                    if (finished && !hadPlayback) {
                        macros.firstOrNull { it.id == BotState.selectedMacroId.value }
                            ?.let { startPlayback(it) }
                    }
                    refreshAutoWalls()
                }
            }
        }
    }

    private fun dragListener(
        params: WindowManager.LayoutParams,
        kind: PlacementKind,
        onTap: () -> Unit
    ): View.OnTouchListener {
        var activePointer = MotionEvent.INVALID_POINTER_ID
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        val slop = ViewConfiguration.get(uiContext).scaledTouchSlop.toFloat()

        fun resetBubbleScale() {
            controllerView?.findViewById<View>(R.id.bubbleVisual)?.apply {
                animate().cancel()
                scaleX = 1f
                scaleY = 1f
            }
        }

        fun finishDrag(cancelled: Boolean) {
            flushDragFrame()
            if (moved) {
                if (kind == PlacementKind.BUBBLE) {
                    snapBubbleToEdge(params)
                } else {
                    savePosition(kind, params)
                }
            } else if (!cancelled) {
                onTap()
            }
            resetBubbleScale()
            activePointer = MotionEvent.INVALID_POINTER_ID
            updateControllerPassthrough()
        }

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    collapseAnimator?.cancel()
                    autoOpenAnimator?.cancel()
                    activePointer = event.getPointerId(0)
                    startX = params.x
                    startY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    if (kind == PlacementKind.BUBBLE) {
                        controllerView?.findViewById<View>(R.id.bubbleVisual)?.apply {
                            animate().cancel()
                            animate().scaleX(0.96f).scaleY(0.96f).setDuration(90L).start()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // A second finger never starts a second drag; keep following
                    // the original pointer until it lifts.
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(activePointer)
                    if (index < 0) return@OnTouchListener true
                    val pointerX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        event.getRawX(index)
                    } else {
                        event.getX(index) + params.x
                    }
                    val pointerY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        event.getRawY(index)
                    } else {
                        event.getY(index) + params.y
                    }
                    val dx = pointerX - downX
                    val dy = pointerY - downY
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        val point = clampCurrentWindow(
                            (startX + dx).roundToInt(),
                            (startY + dy).roundToInt(),
                            kind
                        )
                        scheduleDragFrame(point, kind)
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointer) {
                        finishDrag(cancelled = false)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (activePointer == MotionEvent.INVALID_POINTER_ID) return@OnTouchListener true
                    finishDrag(cancelled = false)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (activePointer == MotionEvent.INVALID_POINTER_ID) return@OnTouchListener true
                    finishDrag(cancelled = true)
                    activePointer = MotionEvent.INVALID_POINTER_ID
                    savePosition(kind, params)
                    true
                }

                else -> true
            }
        }
    }

    private fun scheduleDragFrame(point: OverlayPoint, kind: PlacementKind) {
        pendingDragPoint = point
        pendingDragKind = kind
        if (dragFrameCallback != null) return
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                dragFrameCallback = null
                applyPendingDragFrame()
            }
        }
        dragFrameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun applyPendingDragFrame() {
        val point = pendingDragPoint ?: return
        val view = controllerView ?: return
        val params = controllerParams ?: return
        params.x = point.x
        params.y = point.y
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached mid drag.
        }
    }

    private fun flushDragFrame() {
        dragFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        dragFrameCallback = null
        applyPendingDragFrame()
        pendingDragPoint = null
        pendingDragKind = null
    }

    private fun safeBounds(): OverlayBounds {
        if (screenWidth <= 0 || screenHeight <= 0) updateScreenSize()
        var left = 0
        var top = 0
        var right = screenWidth
        var bottom = screenHeight
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.captionBar()
                )
                left = insets.left
                top = insets.top
                right = metrics.bounds.width() - insets.right
                bottom = metrics.bounds.height() - insets.bottom
            } catch (_: Exception) {
                // Fall back to the real screen size.
            }
        }
        if (right <= left || bottom <= top) {
            left = 0
            top = 0
            right = screenWidth
            bottom = screenHeight
        }
        return OverlayBounds(left, top, right, bottom)
    }

    private fun positionKey(kind: PlacementKind): String =
        "${kind.key}_${currentOrientation()}"

    private fun savePosition(kind: PlacementKind, params: WindowManager.LayoutParams) {
        if (kind == PlacementKind.RECORDING && !recording && !startingPassive) return
        overlayPositions?.set(positionKey(kind), OverlayPoint(params.x, params.y))
    }

    private fun savedPosition(kind: PlacementKind): OverlayPoint? =
        overlayPositions?.get(positionKey(kind))

    private fun defaultBubblePoint(): OverlayPoint {
        val size = currentBubbleSizePx()
        return OverlayPlacement.defaultBubble(
            settingsStore.settings.value.bubbleCorner,
            size,
            size,
            safeBounds(),
            dp(8)
        )
    }

    private fun defaultControllerPoint(panelWidth: Int, panelHeight: Int): OverlayPoint {
        val start = OverlayPlacement.menuDefault(safeBounds(), dp(8), dp(96))
        return OverlayPlacement.clamp(
            start.x,
            start.y,
            panelWidth,
            panelHeight,
            safeBounds(),
            dp(8)
        )
    }

    private fun clampCurrentWindow(x: Int, y: Int, kind: PlacementKind): OverlayPoint {
        val width = if (kind == PlacementKind.BUBBLE) {
            currentBubbleSizePx()
        } else {
            controllerView?.width?.takeIf { it > 0 } ?: dp(304)
        }
        val height = if (kind == PlacementKind.BUBBLE) {
            currentBubbleSizePx()
        } else {
            controllerView?.height?.takeIf { it > 0 } ?: dp(260)
        }
        val margin = dp(8)
        return OverlayPlacement.clamp(x, y, width, height, safeBounds(), margin)
    }

    private fun applyWindowPoint(
        params: WindowManager.LayoutParams,
        point: OverlayPoint,
        kind: PlacementKind,
        save: Boolean = true
    ) {
        params.x = point.x
        params.y = point.y
        val view = controllerView
        if (view != null) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
                // Ignore.
            }
        }
        if (save) savePosition(kind, params)
        view?.post { refreshControllerBoundsSnapshot() }
    }

    private fun snapBubbleToEdge(params: WindowManager.LayoutParams) {
        val size = currentBubbleSizePx()
        val safe = safeBounds()
        val target = OverlayPlacement.snapToEdges(
            params.x,
            params.y,
            size,
            size,
            safe,
            dp(8),
            dp(24)
        )
        if (target.x == params.x && target.y == params.y) {
            savePosition(PlacementKind.BUBBLE, params)
            refreshControllerBoundsSnapshot()
            return
        }
        snapAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            applyWindowPoint(params, target, PlacementKind.BUBBLE)
            return
        }
        val startX = params.x
        val startY = params.y
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val t = animation.animatedFraction
                params.x = (startX + (target.x - startX) * t).roundToInt()
                params.y = (startY + (target.y - startY) * t).roundToInt()
                controllerView?.let { view ->
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                        // Ignore.
                    }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    snapAnimator = null
                    savePosition(PlacementKind.BUBBLE, params)
                    refreshControllerBoundsSnapshot()
                }
            })
        }
        snapAnimator = animator
        animator.start()
    }

    private fun collapseController() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        if (recording || startingPassive || preparingPassive || playbackChromeActive ||
            player?.isRunning() == true ||
            BotState.state.value == ControllerState.PLAYING ||
            BotState.state.value == ControllerState.PLAYBACK_PAUSED
        ) {
            return
        }
        savePosition(PlacementKind.CONTROLLER, params)
        val size = currentBubbleSizePx()
        val hadSavedIcon = savedPosition(PlacementKind.BUBBLE)
        val autoDock = settingsStore.settings.value.autoDockIcon
        val target = if (autoDock) {
            defaultBubblePoint()
        } else {
            hadSavedIcon ?: bubblePointFromLogo(view, size)
        }
        val clamped = OverlayPlacement.clamp(
            target.x,
            target.y,
            size,
            size,
            safeBounds(),
            dp(8)
        )
        val panel = view.findViewById<View>(R.id.panel)
        val bubble = view.findViewById<View>(R.id.bubble)
        collapseAnimator?.cancel()
        snapAnimator?.cancel()
        val animate = ValueAnimator.areAnimatorsEnabled()
        if (!animate) {
            collapsed = true
            panel.visibility = View.GONE
            panel.scaleX = 1f
            panel.scaleY = 1f
            panel.alpha = 1f
            bubble.visibility = View.VISIBLE
            applyWindowPoint(params, clamped, PlacementKind.BUBBLE, save = false)
            if (!autoDock && hadSavedIcon != null) savePosition(PlacementKind.BUBBLE, params)
            refreshUi()
            return
        }
        val logo = view.findViewById<View>(R.id.logoFrame)
        val panelLoc = IntArray(2)
        val logoLoc = IntArray(2)
        panel.getLocationOnScreen(panelLoc)
        (logo ?: panel).getLocationOnScreen(logoLoc)
        panel.pivotX = (logoLoc[0] - panelLoc[0] + (logo?.width ?: 0) / 2f)
        panel.pivotY = (logoLoc[1] - panelLoc[1] + (logo?.height ?: 0) / 2f)
        val baseFlags = params.flags
        params.flags = baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLLAPSE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val t = animation.animatedFraction
                val scale = 1f - 0.82f * t
                panel.scaleX = scale
                panel.scaleY = scale
                panel.alpha = 1f - t
            }
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    collapseAnimator = null
                    panel.scaleX = 1f
                    panel.scaleY = 1f
                    panel.alpha = 1f
                    if (cancelled) {
                        panel.visibility = View.VISIBLE
                        params.flags = baseFlags
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                            // Window detached.
                        }
                        return
                    }
                    if (playbackChromeActive || recordingChromeActive || reviewChromeActive) {
                        panel.visibility = View.VISIBLE
                        bubble.visibility = View.GONE
                        params.flags = baseFlags
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                            // Window detached.
                        }
                        return
                    }
                    collapsed = true
                    panel.visibility = View.GONE
                    bubble.visibility = View.VISIBLE
                    params.flags = baseFlags
                    applyWindowPoint(params, clamped, PlacementKind.BUBBLE, save = false)
                    if (!autoDock && hadSavedIcon != null) savePosition(PlacementKind.BUBBLE, params)
                    refreshUi()
                }
            })
        }
        collapseAnimator = animator
        animator.start()
    }

    private fun bubblePointFromLogo(view: View, size: Int): OverlayPoint {
        val anchor = view.findViewById<View>(R.id.logoFrame)
            ?: view.findViewById<View>(R.id.panel)
            ?: return defaultBubblePoint()
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        return OverlayPoint(
            location[0] + anchor.width / 2 - size / 2,
            location[1] + anchor.height / 2 - size / 2
        )
    }

    private fun expandController() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        cancelAutoOpen()
        collapseAnimator?.cancel()
        collapseAnimator = null
        val panelView = view.findViewById<View>(R.id.panel)
        panelView.scaleX = 1f
        panelView.scaleY = 1f
        panelView.alpha = 1f
        collapsed = false
        view.findViewById<View>(R.id.bubble).visibility = View.GONE
        val panel = view.findViewById<View>(R.id.panel)
        panel.visibility = View.VISIBLE
        applyPanelWidth(settings = false)
        val panelWidth = panel.width.takeIf { it > 0 } ?: controllerPanelWidthPx()
        val panelHeight = panel.height.takeIf { it > 0 } ?: controllerPanelHeightPx()
        val saved = savedPosition(PlacementKind.CONTROLLER)
            ?: defaultControllerPoint(panelWidth, panelHeight)
        val clamped = OverlayPlacement.clamp(
            saved.x,
            saved.y,
            panelWidth,
            panelHeight,
            safeBounds(),
            dp(8)
        )
        applyWindowPoint(params, clamped, PlacementKind.CONTROLLER)
        refreshUi()
    }

    private fun resetControllerPosition() {
        snapAnimator?.cancel()
        overlayPositions?.clear()
        val view = controllerView
        val params = controllerParams
        if (view == null || params == null) {
            toast("Position reset; open the controller to see it")
            return
        }
        val point = if (collapsed) {
            defaultBubblePoint()
        } else {
            val panel = view.findViewById<View>(R.id.panel)
            defaultControllerPoint(
                panel?.width?.takeIf { it > 0 } ?: controllerPanelWidthPx(),
                panel?.height?.takeIf { it > 0 } ?: dp(260)
            )
        }
        applyWindowPoint(
            params,
            point,
            if (collapsed) PlacementKind.BUBBLE else PlacementKind.CONTROLLER
        )
        toast("Floating position reset")
    }

    // -------------------------------------------------------------- recording

    private fun startRecording() {
        if (recording || startingPassive || preparingPassive) return
        cancelAutoOpen()
        if (BotState.state.value.isPlayback) stopPlayback()
        cancelAutoOpen()
        if (controllerView == null) ensureController()
        if (controllerView == null) return
        // The always-on watcher must not poll or hide the overlay while the
        // user is recording a macro.
        abortCaptureSession()
        val mode = settingsStore.settings.value.recordingMode
        hapticEvent()
        AppEventLog.log("recording: press mode=$mode access=${BotState.state.value}")

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

        when (mode) {
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
        preparingPassive = true
        startingPassive = false
        recording = false
        BotState.clearRecording()
        BotState.setState(ControllerState.READY)
        BotState.setStatus("Checking Shizuku…")
        AppEventLog.log("recording: prepare requested")
        val token = ++prepareToken
        prepareJob = scope.launch { preparePassiveCapture(token) }
    }

    private suspend fun preparePassiveCapture(token: Int) {
        var source: InputEventSource? = null
        var recorder: PassiveRecorder? = null
        var adopted = false
        try {
            AppEventLog.log("recording: creating input source")
            source = withContext(Dispatchers.IO) {
                InputAccessManager.createSource(this@FloatingMenuService)
            }
            if (source == null) {
                val reason = InputAccessManager.unavailableMessage(this@FloatingMenuService)
                AppEventLog.log("recording: refused before start: $reason")
                failPassiveStart(token, reason)
                showOpenAppConfirm(
                    "Recording access needed",
                    "$reason Open AutoClash settings?",
                    "Open app"
                ) {
                    startActivity(
                        Intent(this@FloatingMenuService, SettingsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return
            }
            prepareSource = source
            if (token != prepareToken || !preparingPassive) return

            BotState.setStatus("Reading touchscreen…")
            AppEventLog.log("recording: ${source.label} ready, discovering touchscreen")
            val discovery = withContext(Dispatchers.IO) { source.discover() }
            val device: TouchscreenDevice? = discovery?.let {
                GetEventDiscovery.pickTouchscreen(it)
            }
            if (device == null) {
                failPassiveStart(
                    token,
                    source.lastFailure ?: "Could not find the touchscreen."
                )
                return
            }
            AppEventLog.log(
                "recording: touchscreen ${device.path} ${device.maxX}x${device.maxY} " +
                    "slots=${device.maxSlots}"
            )
            if (token != prepareToken || !preparingPassive) return

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
                },
                onTouch = { pointerId, point, active ->
                    handler.post {
                        effectsView?.recordingTouch(pointerId, point.x, point.y, active)
                    }
                }
            )
            recorder = newRecorder
            BotState.setStatus("Starting capture…")
            AppEventLog.log("recording: starting getevent on ${device.path}")
            val failure = withContext(Dispatchers.IO) { newRecorder.start(device) }
            if (failure != null) {
                failPassiveStart(token, failure)
                return
            }
            if (token != prepareToken || !preparingPassive) return

            adopted = true
            passiveRecorder = newRecorder
            preparingPassive = false
            startingPassive = false
            recording = true
            prepareSource = null
            prepareJob = null
            recordStartMs = SystemClock.uptimeMillis()
            setControllerKeepScreenOn(true)
            enterRecordingChrome()
            BotState.setState(ControllerState.RECORDING)
            BotState.setStatus("Recording passive — touches reach the game")
            handler.removeCallbacks(timerRunnable)
            handler.post(timerRunnable)
            AppEventLog.log(
                "recording: started via ${source.label} ${device.path}"
            )
            toast("Passive recording started. Swipe now.")
            updateRecordingNotification()
            refreshUi()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            failPassiveStart(
                token,
                "Passive capture failed: ${t.message ?: "unknown error"}"
            )
        } finally {
            if (!adopted) {
                withContext(NonCancellable + Dispatchers.IO) {
                    recorder?.stop()
                    recorder?.close()
                    if (recorder == null) source?.close()
                }
            }
        }
    }

    private fun failPassiveStart(token: Int, message: String) {
        if (token != prepareToken) return
        preparingPassive = false
        startingPassive = false
        recording = false
        activeRecordingMode = null
        prepareSource = null
        prepareJob = null
        handler.removeCallbacks(timerRunnable)
        setControllerKeepScreenOn(false)
        BotState.setState(ControllerState.READY)
        BotState.setStatus(message)
        AppEventLog.log("recording: start failed: $message")
        toast(message)
        refreshUi()
        if (autoWatchEnabled) {
            scope.launch {
                delay(1_000)
                startAutoWatch()
            }
        }
    }

    private fun cancelPassivePrepare(reason: String) {
        if (!preparingPassive && prepareJob == null) return
        prepareToken++
        preparingPassive = false
        startingPassive = false
        recording = false
        activeRecordingMode = null
        prepareJob?.cancel()
        prepareJob = null
        val source = prepareSource
        prepareSource = null
        if (source != null) {
            scope.launch(Dispatchers.IO) { source.close() }
        }
        handler.removeCallbacks(timerRunnable)
        setControllerKeepScreenOn(false)
        BotState.setState(ControllerState.READY)
        BotState.setStatus("Capture cancelled")
        AppEventLog.log("recording: prepare $reason")
        refreshUi()
    }

    private fun onPassiveGesture(gesture: BuiltGesture) {
        if (!recording || activeRecordingMode != RecordingMode.PASSIVE) return
        if (BotState.state.value == ControllerState.RECORDING_PAUSED) return
        Log.d(TAG, "Passive gesture: ${gesture.paths.size} path(s), ${gesture.durationMs} ms")
        commitPassiveGesture(gesture)
    }

    private fun onPassiveError(message: String) {
        if (preparingPassive) {
            failPassiveStart(prepareToken, message)
            return
        }
        if (!recording || activeRecordingMode != RecordingMode.PASSIVE) return
        Log.e(TAG, "Passive recording stopped: $message")
        AppEventLog.log("recording: runtime error: $message")
        stopRecording(failure = message)
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
            BotState.setStatus("Recording passive — ${Plural.of(recorded.size, "gesture")}")
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
        if (preparingPassive) {
            toast("Capture is starting…")
            return
        }
        if (startingPassive) {
            toast("Capture is starting…")
            return
        }
        val passive = activeRecordingMode == RecordingMode.PASSIVE
        when (BotState.state.value) {
            ControllerState.RECORDING -> {
                pausedAtMs = SystemClock.uptimeMillis()
                BotState.setState(ControllerState.RECORDING_PAUSED)
                AppEventLog.log("recording: paused gestures=${recorded.size}")
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
                AppEventLog.log("recording: resumed gestures=${recorded.size}")
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
        val density = resources.displayMetrics.density
        val visualPx = (BUBBLE_SIZE_DP * bubbleScale * density)
            .roundToInt()
            .coerceAtLeast(1)
        val minTouch = (MIN_TOUCH_DP * density).roundToInt()
        val touchPx = maxOf(bubbleDp(BUBBLE_SIZE_DP), minTouch, visualPx) + bubbleDp(8)
        view.findViewById<View>(R.id.bubble)?.let { bubble ->
            bubble.layoutParams = bubble.layoutParams.apply {
                width = touchPx
                height = touchPx
            }
        }
        view.findViewById<View>(R.id.bubbleVisual)?.let { visual ->
            visual.layoutParams = visual.layoutParams.apply {
                width = visualPx
                height = visualPx
            }
        }
        view.findViewById<View>(R.id.bubbleGlow)?.let { glow ->
            val glowPx = visualPx + bubbleDp(8)
            glow.layoutParams = glow.layoutParams.apply {
                width = glowPx
                height = glowPx
            }
        }
        view.findViewById<View>(R.id.bubbleLogoFrame)?.let { logo ->
            val tile = (visualPx * 0.85f).roundToInt().coerceAtLeast(1)
            logo.layoutParams = logo.layoutParams.apply {
                width = tile
                height = tile
                if (this is android.widget.FrameLayout.LayoutParams) gravity = Gravity.CENTER
            }
        }
        view.findViewById<ImageView>(R.id.bubbleIcon)?.setPadding(0, 0, 0, 0)
        view.findViewById<TextView>(R.id.bubbleStatus)?.textSize =
            (6f * bubbleScale).coerceAtLeast(4.5f)
    }

    private fun currentBubbleVisualPx(): Int {
        val prefs = settingsStore.settings.value
        val density = resources.displayMetrics.density
        return (BUBBLE_SIZE_DP * prefs.bubbleScale * density).roundToInt().coerceAtLeast(1)
    }

    private fun currentBubbleSizePx(): Int {
        val prefs = settingsStore.settings.value
        val density = resources.displayMetrics.density
        val visual = (BUBBLE_SIZE_DP * prefs.bubbleScale * density)
            .roundToInt()
            .coerceAtLeast(1)
        val minTouch = (MIN_TOUCH_DP * density).roundToInt()
        return maxOf(bubbleDp(BUBBLE_SIZE_DP), minTouch, visual) + bubbleDp(8)
    }

    private fun bubbleDp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private var lastBubbleRing = Int.MIN_VALUE
    private var lastBubbleBadge = ""
    private var ringPulseAnimator: ValueAnimator? = null

    private fun applyBubbleChrome(view: View) {
        val state = BotState.state.value
        val recordingNow = state == ControllerState.RECORDING
        val ringColor = when {
            recordingNow -> Ds.RECORD
            state == ControllerState.RECORDING_PAUSED ||
                state == ControllerState.PLAYBACK_PAUSED -> Ds.WARN
            else -> Ds.PLAY
        }
        if (lastBubbleRing != ringColor) {
            lastBubbleRing = ringColor
            val visual = currentBubbleVisualPx()
            view.findViewById<View>(R.id.bubbleRing)?.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = visual * 0.30f
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(
                        (visual * 0.045f).roundToInt().coerceAtLeast(2),
                        ringColor
                    )
                }
            view.findViewById<View>(R.id.bubbleGlow)?.visibility =
                if (recordingNow) View.VISIBLE else View.GONE
        }
        val ring = view.findViewById<View>(R.id.bubbleRing)
        if (recordingNow && ValueAnimator.areAnimatorsEnabled()) {
            if (ringPulseAnimator == null) {
                ringPulseAnimator = ValueAnimator.ofFloat(1f, 0.55f).apply {
                    duration = 700L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { ring?.alpha = it.animatedValue as Float }
                    start()
                }
            }
        } else {
            ringPulseAnimator?.cancel()
            ringPulseAnimator = null
            ring?.alpha = 1f
        }
        val badge = view.findViewById<TextView>(R.id.bubbleBadge)
        val today = System.currentTimeMillis()
        val count = if (collapsed) {
            attackRepository.records.value.count {
                it.confirmed && Math.abs(today - it.startedAt) < 24 * 60 * 60 * 1000L
            }
        } else {
            0
        }
        val label = if (count > 0) (if (count > 9) "9+" else count.toString()) else ""
        if (label != lastBubbleBadge) {
            lastBubbleBadge = label
            if (label.isNotEmpty() && collapsed) {
                badge?.text = label
                badge?.visibility = View.VISIBLE
            } else {
                badge?.visibility = View.GONE
            }
        }
    }

    private fun clampControllerToScreen() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        if (view.width <= 0 || view.height <= 0) return
        if (screenWidth <= 0 || screenHeight <= 0) return
        val kind = if (collapsed) PlacementKind.BUBBLE else PlacementKind.CONTROLLER
        val point = clampCurrentWindow(params.x, params.y, kind)
        if (point.x == params.x && point.y == params.y) return
        params.x = point.x
        params.y = point.y
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
    }

    private fun refreshControllerBoundsSnapshot() {
        if (recordingChromeActive) {
            refreshRecordingBounds()
            return
        }
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
            BotState.setStatus("Recording (blocks game input) — ${Plural.of(recorded.size, "gesture")}")
        }
        if (now - lastNotificationMs > NOTIFICATION_THROTTLE_MS) {
            lastNotificationMs = now
            updateRecordingNotification()
        }
    }

    private fun stopRecording(silent: Boolean = false, failure: String? = null) {
        if (!recording) return
        dismissRecConfirm()
        val wasStarting = startingPassive
        val recorder = passiveRecorder
        passiveRecorder = null
        startingPassive = false
        preparingPassive = false
        recording = false
        activeRecordingMode = null
        hapticEvent()
        Log.i(TAG, "Recording stopped with ${recorded.size} gesture(s)")
        AppEventLog.log(
            "recording: finish gestures=${recorded.size}" +
                (failure?.let { " failure=$it" } ?: "")
        )
        if (recorded.isEmpty() && failure == null) rejectedGestures++
        pathPoints.clear()
        handler.removeCallbacks(timerRunnable)
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
        if (failure != null) {
            BotState.setState(ControllerState.READY)
            BotState.setStatus("Recording stopped: $failure")
            toast(failure)
            updateNotification(getString(R.string.notification_idle))
            refreshUi()
            return
        }
        if (recorded.isEmpty()) {
            BotState.setState(ControllerState.READY)
            BotState.setStatus("Nothing recorded")
            toast("No gestures recorded")
        } else {
            BotState.setState(ControllerState.REVIEW)
            BotState.setStatus("Name this macro")
            hapticEvent()
            beginSaveToast()
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
        dismissRecConfirm()
        stopPassiveRecorder()
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
        if (preparingPassive) {
            toast("Capture is starting…")
            return
        }
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
        cancelAutoOpen()
        playbackRunActive = true
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
        // Choose the strip position before the first injected gesture. The
        // countdown is shown inside that strip, never in a second overlay.
        placeControllerForPlayback()
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
        val newPlayer = MacroPlayer(
            screenWidth,
            screenHeight,
            resultGate,
            onGesturePath = { pathPoints, delayMs, durationMs ->
                effectsView?.playPath(pathPoints, delayMs, durationMs)
            }
        )
        player = newPlayer
        hapticEvent()
        newPlayer.play(
            adjusted,
            scope,
            onFinished = {
                endAttackSession(interrupted = false)
                onPlaybackRunEnded()
            }
        )
        startRecoveryWatchdog(newPlayer)
        if (!newPlayer.isRunning()) {
            abortCaptureSession()
        }
        updateNotification(getString(R.string.notification_playing, macro.name))
        refreshUi()
    }

    /**
     * The playback strip uses the same default position as the recording
     * strip: centred at the top. A recording strip the user dragged is reused
     * so the two strips feel like one control surface.
     */
    private fun placeControllerForPlayback() {
        if (screenWidth <= 0 || screenHeight <= 0) return
        val safe = safeBounds()
        val stripWidth = playbackStripWidthPx()
        val centered = OverlayPoint(
            safe.left + ((safe.width - stripWidth) / 2).coerceAtLeast(0),
            safe.top + dp(8)
        )
        val recording = overlayPositions?.get(positionKey(PlacementKind.RECORDING))
        val chosen = recording ?: centered
        pendingPlaybackPoint = OverlayPlacement.clamp(
            chosen.x,
            chosen.y,
            stripWidth,
            playbackStripHeightPx(),
            safe,
            dp(8)
        )
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
        hapticEvent()
        pendingPlaybackPoint = null
        recoveryWatchdog?.stop()
        recoveryWatchdog = null
        val interrupted = BotState.interrupted.value
        AppEventLog.log("playback stop interrupted=$interrupted")
        val wasRun = playbackRunActive
        player?.stop()
        player = null
        endAttackSession(interrupted)
        if (wasRun) onPlaybackRunEnded()
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
                val active = sessionRepository.sessions.value.firstOrNull { it.active }
                statsScope = if ((active?.attackCount ?: 0) > 0) STATS_SESSION else STATS_ALL
                renderStats()
            }
        }
    }

    private fun endAttackSession(interrupted: Boolean) {
        recoveryWatchdog?.stop()
        recoveryWatchdog = null
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

    private fun onPlaybackRunEnded() {
        if (serviceDestroying || !playbackRunActive) return
        playbackRunActive = false
        val reason = BotState.status.value
        if (reason.startsWith("Reached ") || reason.startsWith("Repeat finished") ||
            reason.startsWith("Clash left")) {
            scope.launch { sessionRepository.setActiveStopReason(reason) }
        }
        if (recording || startingPassive || preparingPassive) return
        pendingAutoOpen = true
        autoOpenTimeout?.let(handler::removeCallbacks)
        val timeout = Runnable { maybeAutoOpen() }
        autoOpenTimeout = timeout
        handler.postDelayed(timeout, AUTO_OPEN_TIMEOUT_MS)
        if (!resultsCapture.isActive()) maybeAutoOpen()
    }

    private fun cancelAutoOpen() {
        pendingAutoOpen = false
        autoOpenTimeout?.let(handler::removeCallbacks)
        autoOpenTimeout = null
        autoOpenAnimator?.cancel()
        autoOpenAnimator = null
        controllerView?.findViewById<View>(R.id.panel)?.alpha = 1f
        val params = controllerParams ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if ((params.flags and notTouchable) != 0) {
            params.flags = params.flags and notTouchable.inv()
            controllerView?.let { view ->
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                    // Window detached.
                }
            }
        }
    }

    private fun maybeAutoOpen() {
        if (!pendingAutoOpen) return
        if (serviceDestroying) {
            cancelAutoOpen()
            return
        }
        if (settingsView != null || recording || startingPassive || preparingPassive ||
            player?.isRunning() == true
        ) {
            cancelAutoOpen()
            return
        }
        pendingAutoOpen = false
        autoOpenTimeout?.let(handler::removeCallbacks)
        autoOpenTimeout = null
        if (!ClahAccessibilityService.isGameForeground()) {
            showAutoOpenNotification()
            return
        }
        cancelAutoOpenNotification()
        if (controllerView == null) {
            ensureController()
            return
        }
        animateAutoOpen()
    }

    private fun animateAutoOpen() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        val wasCollapsed = collapsed
        cancelAutoOpen()
        val target = defaultControllerPoint(
            controllerPanelWidthPx(),
            view.findViewById<View>(R.id.panel)?.height?.takeIf { it > 0 }
                ?: controllerPanelHeightPx()
        )
        val startX = params.x
        val startY = params.y
        val baseFlags = params.flags
        params.flags = baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishAutoOpen(view, params, target, baseFlags, wasCollapsed)
            return
        }
        val move = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AUTO_OPEN_MOVE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val t = animation.animatedFraction
                params.x = (startX + (target.x - startX) * t).roundToInt()
                params.y = (startY + (target.y - startY) * t).roundToInt()
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                    // Window detached.
                }
            }
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    autoOpenAnimator = null
                    finishAutoOpen(view, params, target, baseFlags, wasCollapsed)
                }
            })
        }
        autoOpenAnimator = move
        move.start()
    }

    private fun finishAutoOpen(
        view: View,
        params: WindowManager.LayoutParams,
        target: OverlayPoint,
        baseFlags: Int,
        wasCollapsed: Boolean
    ) {
        params.x = target.x
        params.y = target.y
        collapsed = false
        view.findViewById<View>(R.id.bubble).visibility = View.GONE
        val panel = view.findViewById<View>(R.id.panel)
        panel.visibility = View.VISIBLE
        panel.alpha = 1f
        applyPanelWidth(settings = false)
        params.flags = baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
        refreshUi()
        savePosition(PlacementKind.CONTROLLER, params)
        view.post { refreshControllerBoundsSnapshot() }
        view.post { clampControllerToScreen() }
        if (!wasCollapsed) {
            params.flags = baseFlags
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
                // Window detached.
            }
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            params.flags = baseFlags
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
                // Window detached.
            }
            return
        }
        panel.alpha = 0f
        val fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AUTO_OPEN_FADE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { panel.alpha = it.animatedFraction }
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    autoOpenAnimator = null
                    params.flags = baseFlags
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                        // Window detached.
                    }
                    refreshControllerBoundsSnapshot()
                }
            })
        }
        autoOpenAnimator = fade
        fade.start()
    }

    private fun showAutoOpenNotification() {
        try {
            ensureChannel()
            val openMenu = PendingIntent.getService(
                this,
                3,
                Intent(this, FloatingMenuService::class.java).setAction(ACTION_OPEN),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.playback_finished_title))
                .setContentText(getString(R.string.playback_finished_text))
                .setContentIntent(openMenu)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, getString(R.string.action_open_menu), openMenu)
                .build()
            getSystemService(NotificationManager::class.java)
                ?.notify(AUTO_OPEN_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            // Notification permission/state is best effort.
        }
    }

    private fun cancelAutoOpenNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                ?.cancel(AUTO_OPEN_NOTIFICATION_ID)
        } catch (_: Exception) {
            // Best effort.
        }
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
        scope.launch { GameSessionTracker.ensureSession(startedAt) }
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
        val current = sessions.firstOrNull { it.active }
            ?: sessions.firstOrNull { it.attackCount > 0 || it.macroIds.isNotEmpty() }
        val scoped = if (statsScope == STATS_SESSION) {
            if (current == null) {
                emptyList()
            } else {
                all.filter {
                    it.id in current.attackIds ||
                        (current.active && it.startedAt >= current.startedAt)
                }
            }
        } else {
            all
        }
        val totals = SessionAggregator.totals(scoped)

        val scopeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scopeRow.addView(
            scopeSegment(),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        scopeRow.addView(
            watchActionChip(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        )
        content.addView(scopeRow)
        content.addView(Ds.spacer(this, 8))
        content.addView(lootStackCard(totals))
        content.addView(statsMetaRow(totals))

        if (totals.incompleteAttacks > 0) {
            content.addView(
                Ds.text(uiContext, 10f, Ds.WARN).apply {
                    text = Plural.needReview(totals.incompleteAttacks) + " — loot read so far is counted"
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }

        val recentHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        recentHeader.addView(
            Ds.text(uiContext, 10.5f, Ds.MUTED, caps = true).apply { text = "Recent" },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(recentHeader)

        if (scoped.size < 3) {
            content.addView(
                Ds.text(uiContext, 10.5f, Ds.MUTED).apply {
                    text = when {
                        scoped.isNotEmpty() -> "Only ${Plural.attacks(scoped.size)} so far."
                        statsScope == STATS_SESSION && current != null ->
                            "No attacks in this session yet."
                        statsScope == STATS_SESSION ->
                            "No session yet. Switch to All time or launch Clash."
                        else -> "No attacks yet."
                    }
                    setPadding(0, dp(4), 0, dp(6))
                }
            )
        }
        scoped.take(5).forEachIndexed { index, record ->
            content.addView(recentAttackRow(record))
            if (index < minOf(4, scoped.size - 1)) content.addView(Ds.divider(this))
        }
        updateWatchActions()
        scheduleTouchTargets()
    }

    private fun scopeSegment(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ds.rounded(Ds.INPUT, 10, this@FloatingMenuService)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        listOf(STATS_SESSION to "Session", STATS_ALL to "All time").forEach { (scope, label) ->
            val selected = statsScope == scope
            val button = Ds.text(uiContext, 11f, Ds.MUTED, bold = true).apply {
                text = label
                gravity = Gravity.CENTER
                minHeight = dp(26)
                setPadding(dp(10), 0, dp(10), 0)
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
            row.addView(button, LinearLayout.LayoutParams(0, dp(26), 1f))
        }
        return row
    }

    private fun watchActionChip(): TextView {
        val chip = Ds.text(uiContext, 11f, Ds.MUTED, bold = true).apply {
            minHeight = dp(26)
            gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            setOnClickListener {
                haptic()
                if (autoWatch) {
                    stopAutoWatch()
                } else {
                    autoWatchEnabled = true
                    startAutoWatch()
                }
                renderStats()
            }
        }
        autoScanChip = chip
        return chip
    }

    private fun lootStackCard(totals: com.clahbot.data.models.SessionTotals): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.rounded(
                Ds.PANEL,
                Ds.RADIUS_CARD,
                this@FloatingMenuService,
                Ds.PANEL_BORDER
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        fun addLootRow(
            iconRes: Int,
            label: String,
            total: Long,
            bonus: Long
        ) {
            val row = LootRowLayout(this).apply {
                setPadding(0, dp(5), 0, dp(5))
            }
            row.icon.setImageResource(iconRes)
            row.left.addView(Ds.text(uiContext, 12.5f, Ds.TEXT, bold = true).apply {
                this.text = label
                isSingleLine = true
                maxLines = 1
            })
            row.left.addView(
                Ds.text(uiContext, 10f, Ds.MUTED).apply {
                    this.text = "loot " + String.format(Locale.US, "%,d", total - bonus) +
                        " + bonus " + String.format(Locale.US, "%,d", bonus)
                    isSingleLine = true
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            row.total.text = String.format(Locale.US, "%,d", total)
            card.addView(row)
        }
        addLootRow(R.drawable.ic_gold, "Gold", totals.totalGold, totals.bonusGold)
        card.addView(Ds.divider(this))
        addLootRow(R.drawable.ic_elixir, "Elixir", totals.totalElixir, totals.bonusElixir)
        card.addView(Ds.divider(this))
        addLootRow(
            R.drawable.ic_dark,
            "Dark elixir",
            totals.totalDarkElixir,
            totals.bonusDarkElixir
        )
        return card
    }

    private fun updateWatchActions() {
        autoScanChip?.apply {
            text = if (autoWatchEnabled) "Auto-scan on" else "Auto-scan off"
            setTextColor(if (autoWatchEnabled) Ds.SUCCESS else Ds.MUTED)
            background = Ds.pill(
                if (autoWatchEnabled) Ds.SUCCESS_BG else Ds.BUTTON,
                this@FloatingMenuService,
                if (autoWatchEnabled) null else Ds.SWITCH_OFF_BORDER
            )
        }
        scanNowLink?.apply {
            text = if (manualWatch) "Stop scan" else "Scan now"
            setTextColor(if (manualWatch) Ds.WARN else Ds.LINK)
        }
    }


    private fun statsMetaRow(totals: com.clahbot.data.models.SessionTotals): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        fun cell(label: String, value: String): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(Ds.text(uiContext, 10f, Ds.MUTED).apply {
                    text = label
                })
                addView(Ds.text(uiContext, 13f, Ds.TEXT, bold = true).apply {
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
            minimumHeight = dp(40)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(Ds.text(uiContext, 12f, Ds.TEXT, bold = true).apply {
            text = formatClockOfDay(record.startedAt)
            fontFeatureSettings = "tnum"
        })
        left.addView(title)
        val sub = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sub.addView(Ds.text(uiContext, 10f, Ds.MUTED).apply {
            text = formatDuration(record.durationMs)
            setPadding(0, 0, dp(6), 0)
        })
        statusChip(record)?.let { sub.addView(it) }
        left.addView(sub)
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
        return Ds.text(uiContext, 9f, color, bold = true).apply {
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
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            })
            addView(Ds.text(uiContext, 10f, Ds.TEXT, bold = true).apply {
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
            text = if (macros.isEmpty()) "MACROS · 0" else "MACROS · ${macros.size}"
            isAllCaps = true
            letterSpacing = 0.08f
            setTextColor(Ds.MUTED)
            textSize = 10.5f
        }

        if (macros.isEmpty()) {
            container.addView(TextView(uiContext).apply {
                text = "No macros yet. Record one to get started."
                setTextColor(Ds.MUTED)
                textSize = 10.5f
                setPadding(dp(6), dp(10), dp(6), dp(10))
            })
            return
        }

        for (macro in macros) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(6), dp(8))
                minimumHeight = dp(64)
                background = Ds.rounded(
                    if (macro.id == selectedId) Ds.SELECTED_ROW else Ds.PANEL,
                    Ds.RADIUS_ROW,
                    this@FloatingMenuService,
                    if (macro.id == selectedId) Ds.SELECTED_ROW_BORDER else Ds.PANEL_BORDER
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, dp(3), 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener { selectMacro(macro) }
            }
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (macro.pinned) {
                nameRow.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_pin)
                    layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                        marginEnd = dp(4)
                    }
                })
            }
            nameRow.addView(TextView(uiContext).apply {
                text = macro.name
                setTextColor(Ds.TEXT)
                textSize = 12.5f
                includeFontPadding = false
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            info.addView(nameRow)
            info.addView(TextView(uiContext).apply {
                text = "${Plural.of(macro.gestures.size, "gesture")} · ${formatDuration(macro.durationMs)}"
                setTextColor(Ds.MUTED)
                textSize = 10f
                includeFontPadding = false
                maxLines = 1
            })
            info.addView(TextView(uiContext).apply {
                text = MacroTimeline.formatSpeed(macro.speedMultiplier) + " · " +
                    MacroTimeline.repeatLabel(macro.repeatMode) + " · " +
                    macroDelayLabel(macro)
                setTextColor(Ds.META_ACCENT)
                textSize = 10f
                includeFontPadding = false
                maxLines = 1
            })
            row.addView(info)
            val play = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                setOnClickListener {
                    haptic()
                    selectedId = macro.id
                    BotState.setSelectedMacroId(macro.id)
                    renderMacroList()
                    startPlayback(macro)
                }
            }
            play.addView(
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_play_white)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Ds.PLAY)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER)
                }
            )
            row.addView(play)
            row.addView(
                plainIconButton(R.drawable.ic_gear, "Settings for ${macro.name}") {
                    haptic()
                    showMacroSettings(macro)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
            )
            container.addView(row)
        }
        view.findViewById<TextView>(R.id.tvLibraryLabel)?.text = "MACROS · ${macros.size}"
        scheduleTouchTargets()
    }

    private fun plainIconButton(
        iconRes: Int,
        description: String,
        onClick: () -> Unit
    ): ImageButton =
        ImageButton(uiContext).apply {
            setImageResource(iconRes)
            contentDescription = description
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }

    private fun compactIconButton(
        iconRes: Int,
        description: String,
        onClick: () -> Unit
    ): ImageButton =
        ImageButton(uiContext).apply {
            setImageResource(iconRes)
            contentDescription = description
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { onClick() }
        }

    private fun iconButton(iconRes: Int, description: String, onClick: () -> Unit): ImageButton =
        ImageButton(uiContext).apply {
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
        return if (delay <= 0L) "no delay" else formatDelaySeconds(delay) + "s delay"
    }

    private fun defaultMacroName(): String =
        "Macro " + SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date())

    private fun beginSaveToast() {
        if (recorded.isEmpty()) return
        val suggestion = MacroNaming.uniqueDefault(
            defaultMacroName(),
            macros.map { it.name }
        )
        reviewName = ""
        savePending = true
        savedMacro = null
        discardedGestures = null
        val view = controllerView ?: return
        view.findViewById<EditText>(R.id.etMacroName)?.apply {
            // The field starts empty on purpose: a name is asked for, never
            // assumed. The suggestion is only a hint until the user types.
            setText("")
            hint = suggestion
        }
        enterReviewChrome()
        showNamePrompt(view)
        setSaveTyping(true)
        refreshUi()
    }

    /**
     * A recording is never saved on the user's behalf: the panel waits for a
     * name and a Save press. The suggested name is only a starting point.
     */
    private fun showNamePrompt(view: View) {
        view.findViewById<TextView>(R.id.tvSaveCountdown)?.text = "Name it, then Save"
    }

    private fun enterReviewChrome() {
        val view = controllerView ?: return
        recordingChromeActive = false
        recordingMinimized = false
        reviewChromeActive = true
        view.findViewById<View>(R.id.controllerHeader).visibility = View.GONE
        view.findViewById<View>(R.id.tvStatus).visibility = View.GONE
        view.findViewById<View>(R.id.settingsHost).visibility = View.GONE
        view.findViewById<View>(R.id.contentFrame).visibility = View.GONE
        view.findViewById<View>(R.id.readyFooter).visibility = View.GONE
        view.findViewById<View>(R.id.recordingPanel).visibility = View.GONE
        view.findViewById<View>(R.id.recPill).visibility = View.GONE
        view.findViewById<View>(R.id.playPill).visibility = View.GONE
        view.findViewById<View>(R.id.reviewPanel).visibility = View.GONE
        val panel = view.findViewById<LinearLayout>(R.id.panel)
        panel.background = null
        panel.setPadding(0, 0, 0, 0)
        val reviewWidth = minOf(dp(340), (safeBounds().width - dp(16)).coerceAtLeast(dp(260)))
        panel.layoutParams = panel.layoutParams.apply {
            width = reviewWidth
            height = LinearLayout.LayoutParams.WRAP_CONTENT
        }
        listOf(R.id.saveBox, R.id.savedBox, R.id.discardedBox).forEach { id ->
            view.findViewById<View>(id)?.layoutParams = (view.findViewById<View>(id)?.layoutParams
                ?: LinearLayout.LayoutParams(reviewWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
                .apply { width = reviewWidth }
        }
        setSaveTyping(false)
        applyReviewChrome()
        view.requestLayout()
        view.post { clampControllerToScreen() }
    }

    private enum class ReviewPhase { SAVE, SAVED, DISCARDED }

    private var reviewPhase = ReviewPhase.SAVE

    private fun applyReviewChrome() {
        val view = controllerView ?: return
        view.findViewById<View>(R.id.saveBox).visibility =
            if (reviewPhase == ReviewPhase.SAVE) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.savedBox).visibility =
            if (reviewPhase == ReviewPhase.SAVED) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.discardedBox).visibility =
            if (reviewPhase == ReviewPhase.DISCARDED) View.VISIBLE else View.GONE
        view.requestLayout()
        view.post { clampControllerToScreen() }
    }

    private fun setSaveTyping(typing: Boolean) {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params.flags = if (typing) base else base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = if (typing) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
        val input = view.findViewById<EditText>(R.id.etMacroName)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        if (typing) {
            input.requestFocus()
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    private fun saveRecording() {
        if (saveJob?.isActive == true) return
        if (!savePending) {
            if (recorded.isEmpty()) {
                toast("Nothing to save")
            }
            return
        }
        val gestures = recorded.toList()
        if (gestures.isEmpty()) {
            toast("Nothing to save")
            return
        }
        val view = controllerView ?: return
        val input = view.findViewById<EditText>(R.id.etMacroName)
        val entered = input?.text?.toString()?.trim().orEmpty()
        val validation = MacroNaming.validationError(entered, repository.macros.value.map { it.name })
        if (validation != null) {
            toast(validation)
            BotState.setStatus(validation)
            input?.requestFocus()
            setSaveTyping(true)
            return
        }
        val prefs = settingsStore.settings.value
        val macro = Macro(
            id = 0L,
            name = entered,
            createdAt = System.currentTimeMillis(),
            durationMs = MacroTimeline.totalDuration(gestures),
            deviceWidth = screenWidth,
            deviceHeight = screenHeight,
            orientation = currentOrientation(),
            gestures = gestures,
            repeatMode = prefs.defaultRepeat,
            speedMultiplier = MacroTimeline.clampSpeed(prefs.defaultSpeed),
            kind = Macro.KIND_ATTACK
        )
        savePending = false
        setSaveTyping(false)
        view.findViewById<View>(R.id.btnSaveNameSave)?.isEnabled = false
        view.findViewById<View>(R.id.btnSaveNameDiscard)?.isEnabled = false
        AppEventLog.log("recording: saving name=\"$entered\" gestures=${gestures.size}")
        saveJob = scope.launch {
            try {
                when (val result = repository.create(macro)) {
                    is MacroWriteResult.Rejected -> {
                        savePending = true
                        toast(result.message)
                        BotState.setStatus(result.message)
                        input?.requestFocus()
                        setSaveTyping(true)
                    }
                    is MacroWriteResult.Saved -> {
                        val saved = result.macro
                        hapticEvent()
                        savedMacro = saved
                        reviewName = saved.name
                        recorded.clear()
                        BotState.clearRecording()
                        selectedId = saved.id
                        BotState.setSelectedMacroId(saved.id)
                        BotState.setStatus("Saved \"${saved.name}\"")
                        reviewPhase = ReviewPhase.SAVED
                        view.findViewById<TextView>(R.id.tvSavedText).text = "Saved · ${saved.name}"
                        applyReviewChrome()
                        scheduleReviewDismiss(SAVED_UNDO_MS)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppEventLog.log("recording: save failed ${error.message}")
                savePending = true
                toast("Could not save macro")
                BotState.setStatus("Could not save macro")
                input?.requestFocus()
                setSaveTyping(true)
            } finally {
                saveJob = null
                view.findViewById<View>(R.id.btnSaveNameSave)?.isEnabled = true
                view.findViewById<View>(R.id.btnSaveNameDiscard)?.isEnabled = true
            }
        }
    }

    private fun discardReviewedRecording() {
        if (!savePending && savedMacro == null) return
        saveJob?.cancel()
        saveJob = null
        savePending = false
        setSaveTyping(false)
        discardedGestures = recorded.toList()
        recorded.clear()
        BotState.clearRecording()
        hapticEvent()
        reviewPhase = ReviewPhase.DISCARDED
        applyReviewChrome()
        scheduleReviewDismiss(SAVED_UNDO_MS)
    }

    /**
     * Drops the last recorded action and refreshes the strip. It only works on
     * a recording that has not been saved: once a macro exists, Undo never
     * touches it, because that is a delete, not an edit.
     */
    private fun undoLastRecordedAction() {
        haptic()
        if (savedMacro != null && !savePending) {
            toast("This recording is already saved")
            return
        }
        if (recorded.isEmpty()) {
            toast("Nothing to undo yet")
            return
        }
        val removed = recorded.removeAt(recorded.size - 1)
        AppEventLog.log(
            "recording: undo dropped ${removed.gestureType} at ${removed.timestampMs}ms, " +
                "${recorded.size} left"
        )
        BotState.setRecordedCount(recorded.size)
        updateRecordingInfo()
        BotState.setStatus(
            if (recorded.isEmpty()) "Recording — no actions yet"
            else "Recording — ${Plural.of(recorded.size, "gesture")}"
        )
    }

    private fun undoSavedMacro() {
        val saved = savedMacro ?: return
        saveUndoRunnable?.let { handler.removeCallbacks(it) }
        saveUndoRunnable = null
        scope.launch {
            if (!repository.delete(saved.id)) {
                toast("Could not undo — the macro is still saved")
                BotState.setStatus("Undo failed — macro still saved")
                return@launch
            }
            savedMacro = null
            recorded.clear()
            recorded.addAll(saved.gestures)
            reviewName = saved.name
            reviewPhase = ReviewPhase.SAVE
            savePending = true
            BotState.setState(ControllerState.REVIEW)
            BotState.setStatus("Name this macro")
            controllerView?.findViewById<EditText>(R.id.etMacroName)?.apply {
                setText(reviewName)
                setSelection(text.length)
            }
            applyReviewChrome()
            controllerView?.let { showNamePrompt(it) }
        }
    }

    private fun undoDiscardedRecording() {
        val gestures = discardedGestures ?: return
        saveUndoRunnable?.let { handler.removeCallbacks(it) }
        saveUndoRunnable = null
        discardedGestures = null
        recorded.clear()
        recorded.addAll(gestures)
        reviewPhase = ReviewPhase.SAVE
        savePending = true
        BotState.setState(ControllerState.REVIEW)
        BotState.setStatus("Name this macro")
        controllerView?.findViewById<EditText>(R.id.etMacroName)?.apply {
            setText(reviewName)
            setSelection(text.length)
        }
        applyReviewChrome()
        controllerView?.let { showNamePrompt(it) }
    }

    private fun scheduleReviewDismiss(delayMs: Long) {
        saveUndoRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (reviewChromeActive) {
                savedMacro = null
                discardedGestures = null
                reviewName = ""
                controllerView?.findViewById<EditText>(R.id.etMacroName)?.setText("")
                restoreControllerChrome()
                BotState.setState(ControllerState.READY)
                BotState.setStatus("Ready")
                refreshUi()
            }
        }
        saveUndoRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    // ------------------------------------------------------ macro settings

    private var settingsPage = 0

    private fun showMacroSettings(
        macro: Macro,
        initialDraft: MacroSettingsDraft? = null,
        initialPage: Int = 0
    ) {
        val view = controllerView ?: return
        val host = view.findViewById<FrameLayout>(R.id.settingsHost) ?: return
        if (settingsView != null) closeMacroSettings()

        controllerParams?.let { params ->
            params.y = dp(48)
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
                // Window detached.
            }
        }
        view.findViewById<View>(R.id.controllerHeader).visibility = View.GONE
        view.findViewById<View>(R.id.contentFrame).visibility = View.GONE
        view.findViewById<View>(R.id.readyFooter).visibility = View.GONE
        view.findViewById<View>(R.id.tvStatus).visibility = View.GONE
        applyPanelWidth(settings = true)
        setSettingsFocusable(true)

        val draft = initialDraft ?: MacroSettingsDraft.from(macro)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        host.removeAllViews()
        host.addView(root)
        settingsView = root
        settingsMacroId = macro.id
        settingsDraft = draft
        host.visibility = View.VISIBLE

        settingsPage = initialPage.coerceIn(0, 1)
        val error = Ds.text(uiContext, 10f, Ds.DANGER_TEXT).apply { visibility = View.GONE }
        val landscape = false

        fun showError(message: String) {
            error.text = message
            error.visibility = View.VISIBLE
        }

        fun clearError() {
            error.visibility = View.GONE
        }

        fun draftMacro(): Macro = draft.toMacro(macro)

        var settingsSaving = false

        fun persist(onSaved: (Macro) -> Unit) {
            clearError()
            if (settingsSaving) return
            val candidate = draftMacro()
            settingsSaving = true
            scope.launch {
                try {
                    when (val result = repository.updateSettings(candidate)) {
                        is MacroWriteResult.Saved -> onSaved(result.macro)
                        is MacroWriteResult.Rejected -> showError(result.message)
                    }
                } finally {
                    settingsSaving = false
                }
            }
        }

        fun tabButton(text: String, selected: Boolean, onClick: () -> Unit): TextView =
            Ds.text(uiContext, 11f, if (selected) android.graphics.Color.WHITE else Ds.MUTED, bold = true).apply {
                this.text = text
                gravity = Gravity.CENTER
                background = Ds.rounded(
                    if (selected) Ds.ACCENT else android.graphics.Color.TRANSPARENT,
                    8,
                    this@FloatingMenuService
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f)
                isClickable = true
                setOnClickListener {
                    haptic()
                    onClick()
                }
            }

        fun footerButton(text: String, primary: Boolean, onClick: () -> Unit): Button =
            Button(uiContext).apply {
                this.text = text
                textSize = 12f
                isAllCaps = false
                minWidth = 0
                stateListAnimator = null
                setTextColor(if (primary) android.graphics.Color.WHITE else Ds.TEXT)
                background = Ds.rounded(
                    if (primary) Ds.ACCENT else Ds.BUTTON,
                    9,
                    this@FloatingMenuService
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    marginStart = if (primary) dp(8) else 0
                }
                setOnClickListener {
                    haptic()
                    onClick()
                }
            }

        fun autoLabel(text: String, size: Float): TextView {
            val label = Ds.text(uiContext, size, Ds.MUTED).apply {
                this.text = text
                maxLines = 1
            }
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                label,
                10,
                size.toInt().coerceAtLeast(10),
                1,
                TypedValue.COMPLEX_UNIT_SP
            )
            return label
        }

        fun row(label: String, control: View, labelSize: Float = 12.5f): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                setPadding(0, dp(1), 0, dp(1))
            }
            row.addView(
                autoLabel(label, labelSize),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            control.layoutParams = (
                control.layoutParams as? LinearLayout.LayoutParams
                    ?: LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                ).apply { marginStart = dp(6) }
            row.addView(control)
            return row
        }

        fun stepper(
            valueText: () -> String,
            enabled: Boolean = true,
            onMinus: () -> Unit,
            onPlus: () -> Unit,
            onTap: () -> Unit
        ): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val value = Ds.text(
                this,
                13f,
                if (enabled) Ds.TEXT else 0xFF5B6B8A.toInt(),
                bold = true
            ).apply {
                gravity = Gravity.CENTER
                minWidth = dp(34)
                minHeight = dp(40)
                text = valueText()
                fontFeatureSettings = "tnum"
                setOnClickListener {
                    if (enabled) {
                        haptic()
                        onTap()
                    }
                }
            }
            fun refresh() {
                value.text = valueText()
                value.setTextColor(if (enabled) Ds.TEXT else 0xFF5B6B8A.toInt())
            }
            fun stepButton(text: String, onClick: () -> Unit): FrameLayout {
                val holder = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(40))
                }
                holder.addView(
                    TextView(uiContext).apply {
                        this.text = text
                        gravity = Gravity.CENTER
                        textSize = 18f
                        setTextColor(Ds.TEXT)
                        background = Ds.pill(Ds.BUTTON, this@FloatingMenuService)
                        layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
                        setOnClickListener {
                            if (enabled) {
                                haptic()
                                onClick()
                                refresh()
                                clearError()
                            }
                        }
                    }
                )
                return holder
            }
            box.addView(stepButton("−", onMinus))
            box.addView(value)
            box.addView(stepButton("+", onPlus))
            return box
        }

        fun toggle(checked: Boolean, onChange: (Boolean) -> Unit): View {
            var value = checked
            val container = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(40))
            }
            val track = View(this)
            val thumb = View(this).apply { elevation = dp(1).toFloat() }
            fun paint() {
                track.background = Ds.pill(
                    if (value) Ds.SWITCH_ON else Ds.SWITCH_OFF_TRACK,
                    this@FloatingMenuService,
                    if (value) null else Ds.SWITCH_OFF_BORDER
                )
                thumb.background = Ds.pill(Color.WHITE, this@FloatingMenuService)
                thumb.layoutParams = FrameLayout.LayoutParams(
                    dp(18),
                    dp(18),
                    Gravity.CENTER_VERTICAL or if (value) Gravity.END else Gravity.START
                ).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }
            container.addView(
                track,
                FrameLayout.LayoutParams(dp(38), dp(22), Gravity.CENTER)
            )
            container.addView(thumb)
            paint()
            container.setOnClickListener {
                haptic()
                value = !value
                paint()
                onChange(value)
            }
            return container
        }

        fun segmented(
            options: List<Pair<String, String>>,
            isSelected: (String) -> Boolean,
            onSelect: (String) -> Unit
        ): LinearLayout {
            val track = LinearLayout(this).apply {
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
                val button = Ds.text(uiContext, 11f, Ds.MUTED, bold = true).apply {
                    tag = key
                    text = label
                    gravity = Gravity.CENTER
                    maxLines = 1
                    minHeight = dp(34)
                    setPadding(dp(8), 0, dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(44)
                    )
                    setOnClickListener {
                        haptic()
                        onSelect(key)
                        paint()
                        clearError()
                    }
                }
                buttons.add(button)
                track.addView(button)
            }
            paint()
            return track
        }

        var renderPage: () -> Unit = {}

        fun nameField(): TextView = Ds.text(uiContext, 12.5f, Ds.TEXT).apply {
            text = draft.name
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(9), 0, dp(9), 0)
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(34))
            gravity = Gravity.CENTER_VERTICAL
            background = Ds.rounded(Ds.INPUT, 9, this@FloatingMenuService, Ds.PANEL_BORDER)
            isClickable = true
            setOnClickListener {
                haptic()
                openTextEditor("Macro name", draft.name, MacroInputActivity.KIND_NAME) { result ->
                    val trimmed = result?.trim().orEmpty()
                    if (trimmed.isNotEmpty()) {
                        draft.name = trimmed
                        renderPage()
                    }
                }
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            )
        }
        header.addView(compactIconButton(R.drawable.ic_back, "Back to macros") {
            if (settingsPage > 0) {
                settingsPage = 0
                renderPage()
            } else {
                closeMacroSettings()
            }
        })
        header.addView(
            Ds.text(uiContext, 13.5f, Ds.TEXT, bold = true).apply {
                text = macro.name
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val moreButton = compactIconButton(R.drawable.ic_more, "More actions") {}
        moreButton.setOnClickListener {
            showSettingsMenu(moreButton, macro, draft, onDeleted = { showUndo(macro) })
        }
        header.addView(moreButton)
        header.addView(compactIconButton(R.drawable.ic_minimize, "Minimize controller") {
            closeMacroSettings()
            collapseController()
        })
        header.addView(compactIconButton(R.drawable.ic_close, "Close controller") {
            closeMacroSettings()
            requestClose()
        })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ds.rounded(Ds.INPUT, 9, this@FloatingMenuService)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { topMargin = dp(4) }
            gravity = Gravity.CENTER_VERTICAL
        }
        var playbackTab: TextView? = null
        var stopTab: TextView? = null
        playbackTab = tabButton("Playback", settingsPage == 0) {
            settingsPage = 0
            renderPage()
        }
        stopTab = tabButton("Stop conditions", settingsPage == 1) {
            settingsPage = 1
            renderPage()
        }
        tabs.addView(playbackTab)
        tabs.addView(stopTab)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }

        fun updateTabs() {
            val selected = 0xFF2563EB.toInt()
            val clear = android.graphics.Color.TRANSPARENT
            playbackTab?.apply {
                background = Ds.rounded(
                    if (settingsPage == 0) selected else clear,
                    8,
                    this@FloatingMenuService
                )
                setTextColor(if (settingsPage == 0) android.graphics.Color.WHITE else Ds.MUTED)
            }
            stopTab?.apply {
                background = Ds.rounded(
                    if (settingsPage == 1) selected else clear,
                    8,
                    this@FloatingMenuService
                )
                setTextColor(if (settingsPage == 1) android.graphics.Color.WHITE else Ds.MUTED)
            }
        }

        fun addDivider(column: LinearLayout) {
            column.addView(Ds.divider(this))
        }

        fun addRow(column: LinearLayout, label: String, control: View, labelSize: Float = 13f) {
            column.addView(row(label, control, labelSize))
            addDivider(column)
        }

        fun editRepeatCount() {
            openTextEditor(
                "Repeat count",
                draft.repeatCount.toString(),
                MacroInputActivity.KIND_NUMBER
            ) { text ->
                val value = text?.toIntOrNull()
                if (value != null && value in 1..Macro.MAX_CUSTOM_REPEAT) {
                    draft.repeatCount = value
                    draft.repeat = MacroTimeline.customRepeatMode(value)
                    renderPage()
                }
            }
        }

        fun playbackPage() {
            val labelSize = if (landscape) 12f else 13f
            if (landscape) {
                val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val left = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val right = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(12)
                    }
                }
                addRow(left, "Name", nameField(), labelSize)
                addRow(left, "Speed", stepper(
                    { MacroTimeline.formatSpeed(draft.speed) },
                    onMinus = { draft.speed = MacroTimeline.stepSpeed(draft.speed, -1) },
                    onPlus = { draft.speed = MacroTimeline.stepSpeed(draft.speed, 1) },
                    onTap = {
                        openTextEditor(
                            "Speed",
                            draft.speed.toString(),
                            MacroInputActivity.KIND_DECIMAL
                        ) { text ->
                            text?.toFloatOrNull()?.let {
                                draft.speed = MacroTimeline.clampSpeed(it)
                                renderPage()
                            }
                        }
                    }
                ), labelSize)
                addRow(left, "Repeat", segmented(
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
                        renderPage()
                    }
                ), labelSize)
                if (MacroTimeline.isCustomRepeat(draft.repeat)) {
                    addRow(right, "Repeat count", stepper(
                        { draft.repeatCount.toString() },
                        onMinus = {
                            draft.repeatCount = (draft.repeatCount - 1).coerceAtLeast(1)
                        },
                        onPlus = {
                            draft.repeatCount = (draft.repeatCount + 1)
                                .coerceAtMost(Macro.MAX_CUSTOM_REPEAT)
                        },
                        onTap = { editRepeatCount() }
                    ), labelSize)
                }
                addRow(right, "Repeat delay", stepper(
                    { formatDelaySeconds(draft.delayMs) + " s" },
                    onMinus = { draft.delayMs = (draft.delayMs - 200L).coerceAtLeast(0L) },
                    onPlus = { draft.delayMs = (draft.delayMs + 200L).coerceAtMost(Macro.MAX_REPEAT_DELAY_MS) },
                    onTap = {
                        openTextEditor(
                            "Repeat delay (seconds)",
                            formatDelaySeconds(draft.delayMs),
                            MacroInputActivity.KIND_DECIMAL
                        ) { text ->
                            val seconds = text?.toDoubleOrNull()
                            if (seconds != null && seconds.isFinite() && seconds in 0.0..600.0) {
                                draft.delayMs = (seconds * 1000.0).roundToLong()
                                renderPage()
                            }
                        }
                    }
                ), labelSize)
                addRow(right, "Countdown", stepper(
                    { "${draft.countdownSec} s" },
                    onMinus = { draft.countdownSec = (draft.countdownSec - 1).coerceAtLeast(0) },
                    onPlus = { draft.countdownSec = (draft.countdownSec + 1).coerceAtMost(30) },
                    onTap = {
                        openTextEditor(
                            "Countdown (seconds)",
                            draft.countdownSec.toString(),
                            MacroInputActivity.KIND_NUMBER
                        ) { text ->
                            val value = text?.toIntOrNull()
                            if (value != null && value in 0..30) {
                                draft.countdownSec = value
                                renderPage()
                            }
                        }
                    }
                ), labelSize)
                columns.addView(left)
                columns.addView(right)
                content.addView(columns)
            } else {
                addRow(content, "Name", nameField())
                addRow(content, "Speed", stepper(
                    { MacroTimeline.formatSpeed(draft.speed) },
                    onMinus = { draft.speed = MacroTimeline.stepSpeed(draft.speed, -1) },
                    onPlus = { draft.speed = MacroTimeline.stepSpeed(draft.speed, 1) },
                    onTap = {
                        openTextEditor(
                            "Speed",
                            draft.speed.toString(),
                            MacroInputActivity.KIND_DECIMAL
                        ) { text ->
                            text?.toFloatOrNull()?.let {
                                draft.speed = MacroTimeline.clampSpeed(it)
                                renderPage()
                            }
                        }
                    }
                ))
                addRow(content, "Repeat", segmented(
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
                        renderPage()
                    }
                ))
                if (MacroTimeline.isCustomRepeat(draft.repeat)) {
                    addRow(content, "Repeat count", stepper(
                        { draft.repeatCount.toString() },
                        onMinus = {
                            draft.repeatCount = (draft.repeatCount - 1).coerceAtLeast(1)
                        },
                        onPlus = {
                            draft.repeatCount = (draft.repeatCount + 1)
                                .coerceAtMost(Macro.MAX_CUSTOM_REPEAT)
                        },
                        onTap = { editRepeatCount() }
                    ))
                }
                addRow(content, "Repeat delay", stepper(
                    { formatDelaySeconds(draft.delayMs) + " s" },
                    onMinus = { draft.delayMs = (draft.delayMs - 200L).coerceAtLeast(0L) },
                    onPlus = { draft.delayMs = (draft.delayMs + 200L).coerceAtMost(Macro.MAX_REPEAT_DELAY_MS) },
                    onTap = {
                        openTextEditor(
                            "Repeat delay (seconds)",
                            formatDelaySeconds(draft.delayMs),
                            MacroInputActivity.KIND_DECIMAL
                        ) { text ->
                            val seconds = text?.toDoubleOrNull()
                            if (seconds != null && seconds.isFinite() && seconds in 0.0..600.0) {
                                draft.delayMs = (seconds * 1000.0).roundToLong()
                                renderPage()
                            }
                        }
                    }
                ))
                addRow(content, "Countdown", stepper(
                    { "${draft.countdownSec} s" },
                    onMinus = { draft.countdownSec = (draft.countdownSec - 1).coerceAtLeast(0) },
                    onPlus = { draft.countdownSec = (draft.countdownSec + 1).coerceAtMost(30) },
                    onTap = {
                        openTextEditor(
                            "Countdown (seconds)",
                            draft.countdownSec.toString(),
                            MacroInputActivity.KIND_NUMBER
                        ) { text ->
                            val value = text?.toIntOrNull()
                            if (value != null && value in 0..30) {
                                draft.countdownSec = value
                                renderPage()
                            }
                        }
                    }
                ))
            }
        }

        fun stopPage() {
            addRow(content, "Game leaves foreground", toggle(draft.stopOnBackground) {
                draft.stopOnBackground = it
            })
            val minutesStepper = stepper(
                { (draft.stopMinutes ?: 30).toString() },
                enabled = draft.stopMinutes != null,
                onMinus = {
                    draft.stopMinutes = ((draft.stopMinutes ?: 30) - 1)
                        .coerceIn(1, Macro.MAX_STOP_AFTER_MINUTES)
                },
                onPlus = {
                    draft.stopMinutes = ((draft.stopMinutes ?: 30) + 1)
                        .coerceIn(1, Macro.MAX_STOP_AFTER_MINUTES)
                },
                onTap = {
                    openTextEditor(
                        "After minutes",
                        (draft.stopMinutes ?: 30).toString(),
                        MacroInputActivity.KIND_NUMBER
                    ) { text ->
                        val value = text?.toIntOrNull()
                        if (value != null && value in 1..Macro.MAX_STOP_AFTER_MINUTES) {
                            draft.stopMinutes = value
                            renderPage()
                        }
                    }
                }
            )
            val minutesSwitch = toggle(draft.stopMinutes != null) { checked ->
                draft.stopMinutes = if (checked) 30 else null
                minutesStepper.alpha = if (checked) 1f else 0.45f
                renderPage()
            }
            minutesStepper.alpha = if (draft.stopMinutes != null) 1f else 0.45f
            val minutesRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
            }
            minutesRow.addView(
                autoLabel("After minutes", 13f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            minutesRow.addView(minutesStepper)
            minutesRow.addView(minutesSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) })
            content.addView(minutesRow)
            addDivider(content)

            val attacksStepper = stepper(
                { (draft.stopAttacks ?: 10).toString() },
                enabled = draft.stopAttacks != null,
                onMinus = {
                    draft.stopAttacks = ((draft.stopAttacks ?: 10) - 1)
                        .coerceIn(1, Macro.MAX_STOP_AFTER_ATTACKS)
                },
                onPlus = {
                    draft.stopAttacks = ((draft.stopAttacks ?: 10) + 1)
                        .coerceIn(1, Macro.MAX_STOP_AFTER_ATTACKS)
                },
                onTap = {
                    openTextEditor(
                        "After attacks",
                        (draft.stopAttacks ?: 10).toString(),
                        MacroInputActivity.KIND_NUMBER
                    ) { text ->
                        val value = text?.toIntOrNull()
                        if (value != null && value in 1..Macro.MAX_STOP_AFTER_ATTACKS) {
                            draft.stopAttacks = value
                            renderPage()
                        }
                    }
                }
            )
            val attacksSwitch = toggle(draft.stopAttacks != null) { checked ->
                draft.stopAttacks = if (checked) 10 else null
                attacksStepper.alpha = if (checked) 1f else 0.45f
                renderPage()
            }
            attacksStepper.alpha = if (draft.stopAttacks != null) 1f else 0.45f
            val attacksRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
            }
            attacksRow.addView(
                autoLabel("After attacks", 13f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            attacksRow.addView(attacksStepper)
            attacksRow.addView(attacksSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) })
            content.addView(attacksRow)
            addDivider(content)

            addRow(content, "Pin to top", toggle(draft.pinned) { draft.pinned = it })
            val summary = draft.toMacro(macro)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ds.rounded(0xFF14234A.toInt(), 10, this@FloatingMenuService)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            box.addView(Ds.text(uiContext, 11.5f, 0xFFBFD4FF.toInt(), bold = true).apply {
                text = "Runs until the first of:"
            })
            box.addView(Ds.text(uiContext, 11f, Ds.MUTED).apply {
                text = StopSummary.summaryLine(summary)
                setPadding(0, dp(2), 0, 0)
            })
            content.addView(box)
            StopSummary.conflictHint(summary)?.let { hint ->
                content.addView(Ds.text(uiContext, 10.5f, Ds.WARN).apply {
                    text = hint
                    setPadding(dp(2), dp(6), dp(2), dp(2))
                })
            }
            val info = Ds.text(uiContext, 11f, Ds.MUTED).apply {
                val lastPlayed = macro.lastPlayedAt?.let {
                    SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(it))
                } ?: "never"
                text = "${Plural.of(macro.gestures.size, "gesture")} · ${formatDuration(macro.durationMs)} · " +
                    "Played ${Plural.of(macro.playCount, "time")}\nCreated " +
                    SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(macro.createdAt)) +
                    " · Last played $lastPlayed"
                setPadding(0, dp(5), 0, dp(5))
            }
            content.addView(info)
        }

        renderPage = {
            content.removeAllViews()
            if (settingsPage == 0) playbackPage() else stopPage()
            updateTabs()
            content.requestLayout()
            scheduleTouchTargets()
        }

        val swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) && abs(dx) > dp(40).toFloat() && abs(velocityX) > dp(120).toFloat()) {
                    val next = if (dx < 0) 1 else 0
                    if (next != settingsPage) {
                        settingsPage = next
                        renderPage()
                    }
                    return true
                }
                return false
            }
        })
        scroll.setOnTouchListener { _, event ->
            swipeDetector.onTouchEvent(event)
            false
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            setMinimumHeight(dp(40))
            addView(footerButton("Cancel", primary = false) { closeMacroSettings() })
            addView(footerButton("Save", primary = true) {
                persist { saved ->
                    closeMacroSettings()
                    BotState.setStatus("Settings saved for \"${saved.name}\"")
                    toast("Saved")
                }
            })
        }

        root.addView(header)
        root.addView(Ds.divider(this))
        root.addView(tabs)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(2) }
        )
        root.addView(error)
        root.addView(footer)

        renderPage()

        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (settingsPage > 0) {
                    settingsPage = 0
                    renderPage()
                } else {
                    closeMacroSettings()
                }
                true
            } else {
                false
            }
        }
        view.post { clampControllerToScreen() }
    }

    private fun setSettingsFocusable(focusable: Boolean) {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params.flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
    }

    private fun showSettingsMenu(
        anchor: View,
        macro: Macro,
        draft: MacroSettingsDraft,
        onDeleted: () -> Unit
    ) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.rounded(
                Ds.PANEL,
                Ds.RADIUS_CARD,
                this@FloatingMenuService,
                Ds.PANEL_BORDER
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val popup = PopupWindow(
            menu,
            dp(196),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        fun item(label: String, danger: Boolean = false, action: () -> Unit) {
            menu.addView(
                TextView(uiContext).apply {
                    text = label
                    textSize = 13f
                    setTextColor(if (danger) Ds.DANGER_TEXT else Ds.TEXT)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                    minHeight = dp(44)
                    isClickable = true
                    setOnClickListener {
                        haptic()
                        popup.dismiss()
                        action()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44)
                )
            )
        }
        item(if (draft.pinned) "Unpin" else "Pin to top") {
            draft.pinned = !draft.pinned
            scope.launch {
                when (val result = repository.updateSettings(draft.toMacro(macro))) {
                    is MacroWriteResult.Saved -> {
                        closeMacroSettings()
                        toast(if (result.macro.pinned) "Pinned to top" else "Unpinned")
                    }
                    is MacroWriteResult.Rejected -> {
                        draft.pinned = !draft.pinned
                        toast(result.message)
                    }
                }
            }
        }
        item("Duplicate") {
            scope.launch {
                val copy = repository.duplicate(macro.id)
                if (copy == null) {
                    toast("Duplicate failed")
                    return@launch
                }
                val candidate = draft.toMacro(copy).copy(name = copy.name)
                when (val result = repository.updateSettings(candidate)) {
                    is MacroWriteResult.Saved -> {
                        closeMacroSettings()
                        toast("Duplicated as \"${result.macro.name}\"")
                    }
                    is MacroWriteResult.Rejected -> {
                        repository.delete(copy.id)
                        toast(result.message)
                    }
                }
            }
        }
        item("Reset to defaults") {
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
        }
        item("Delete", danger = true) {
            showConfirmDialog(
                title = "Delete macro?",
                message = "\"${macro.name}\" will be removed. You can undo for 5 seconds.",
                positive = "Delete"
            ) {
                scope.launch {
                    if (repository.delete(macro.id)) {
                        closeMacroSettings()
                        BotState.setStatus("Deleted \"${macro.name}\"")
                        onDeleted()
                    } else {
                        toast("Delete failed")
                    }
                }
            }
        }
        popup.showAsDropDown(anchor, -dp(110), dp(4))
    }


    private var inlineOverlay: View? = null

    private fun setOverlayTyping(typing: Boolean) {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params.flags = if (typing) base else base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = if (typing) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Window detached.
        }
        if (!typing) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun showInlineCard(card: View) {
        dismissInlineCard()
        val root = controllerView as? android.view.ViewGroup ?: return
        val dim = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xB30A101D.toInt())
            isClickable = true
        }
        dim.addView(
            card,
            android.widget.FrameLayout.LayoutParams(
                dp(300),
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.addView(
            dim,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        inlineOverlay = dim
        setOverlayTyping(false)
    }

    private fun dismissInlineCard() {
        val overlay = inlineOverlay ?: return
        inlineOverlay = null
        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
        setOverlayTyping(false)
    }

    private fun showOpenAppConfirm(
        title: String,
        message: String,
        positive: String,
        onOpen: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.rounded(Ds.PANEL, 16, this@FloatingMenuService, Ds.PANEL_BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(Ds.text(uiContext, 15f, Ds.TEXT, bold = true).apply { text = title })
        card.addView(Ds.text(uiContext, 12f, Ds.MUTED).apply {
            text = message
            setPadding(0, dp(6), 0, dp(12))
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun button(label: String, primary: Boolean, action: () -> Unit): Button =
            Button(uiContext).apply {
                text = label
                isAllCaps = false
                textSize = 12.5f
                minWidth = 0
                stateListAnimator = null
                setTextColor(Color.WHITE)
                background = Ds.rounded(
                    if (primary) Ds.ACCENT else Ds.BUTTON,
                    9,
                    this@FloatingMenuService
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginStart = if (primary) dp(8) else 0
                }
                setOnClickListener {
                    haptic()
                    dismissInlineCard()
                    action()
                }
            }
        buttons.addView(button("Stay", false) {})
        buttons.addView(button(positive, true, onOpen))
        card.addView(buttons)
        showInlineCard(card)
    }

    private fun openTextEditor(
        title: String,
        value: String,
        kind: String,
        onValue: (String?) -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.rounded(Ds.PANEL, 16, this@FloatingMenuService, Ds.PANEL_BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(Ds.text(uiContext, 15f, Ds.TEXT, bold = true).apply { text = title })
        val input = EditText(uiContext).apply {
            setText(value)
            setSelection(text.length)
            textSize = 14f
            setTextColor(Ds.TEXT)
            setHintTextColor(0xFF5B6B8A.toInt())
            background = Ds.rounded(Ds.INPUT, 10, this@FloatingMenuService, Ds.PANEL_BORDER)
            setPadding(dp(12), 0, dp(12), 0)
            minHeight = dp(42)
            inputType = when (kind) {
                MacroInputActivity.KIND_NUMBER -> android.text.InputType.TYPE_CLASS_NUMBER
                MacroInputActivity.KIND_DECIMAL -> android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                else -> android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply { topMargin = dp(10) }
            setOnClickListener { setOverlayTyping(true); requestFocus() }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    setOverlayTyping(true)
                    requestFocus()
                }
                false
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    val result = text.toString().trim()
                    dismissInlineCard()
                    onValue(result)
                    true
                } else false
            }
        }
        card.addView(input)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): Button =
            Button(uiContext).apply {
                text = label
                isAllCaps = false
                textSize = 12.5f
                minWidth = 0
                stateListAnimator = null
                setTextColor(Color.WHITE)
                background = Ds.rounded(
                    if (primary) Ds.ACCENT else Ds.BUTTON,
                    9,
                    this@FloatingMenuService
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginStart = if (primary) dp(8) else 0
                }
                setOnClickListener { haptic(); onClick() }
            }
        buttons.addView(actionButton("Cancel", false) {
            dismissInlineCard()
            onValue(null)
        })
        buttons.addView(actionButton("Save", true) {
            val result = input.text.toString().trim()
            dismissInlineCard()
            onValue(result)
        })
        card.addView(buttons)
        showInlineCard(card)
    }

    private fun closeMacroSettings() {
        val view = controllerView ?: return
        val host = view.findViewById<FrameLayout>(R.id.settingsHost) ?: return
        settingsView = null
        settingsMacroId = -1L
        settingsDraft = null
        setSettingsFocusable(false)
        controllerView?.setOnKeyListener(null)
        host.removeAllViews()
        host.visibility = View.GONE
        view.findViewById<View>(R.id.controllerHeader).visibility = View.VISIBLE
        applyPanelWidth(settings = false)
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
            renderMacroList()
        }
        undoTicker = ticker
        handler.postDelayed(ticker, UNDO_WINDOW_MS)
        renderMacroList()
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

    // -------------------------------------------------------------------- ui

    private fun recordingUiActive(): Boolean = recording || startingPassive

    /**
     * The strip is critical chrome: it must stay usable even when the user set
     * the controller to 80 %. Its width/height therefore never shrink below
     * the unscaled design size, though they may grow with the controller scale.
     */
    private fun playbackStripWidthPx(): Int {
        val landscape = screenWidth > screenHeight
        val base = if (landscape) PLAY_STRIP_LANDSCAPE_W else PLAY_STRIP_PORTRAIT_W
        val safe = safeBounds()
        val unshrunk = maxOf(dp(base), unscaledDp(base))
        return minOf(
            unshrunk,
            (safe.width - unscaledDp(16)).coerceAtLeast(unscaledDp(260))
        )
    }

    private fun playbackStripHeightPx(): Int =
        maxOf(dp(PLAY_STRIP_HEIGHT), unscaledDp(PLAY_STRIP_HEIGHT))

    private fun unscaledDp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun enterPlaybackChrome(pointOverride: OverlayPoint? = null) {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        playbackChromeActive = true
        playbackMinimized = settingsStore.settings.value.playbackStartMinimized
        collapsed = false
        collapseAnimator?.cancel()
        collapseAnimator = null
        snapAnimator?.cancel()
        snapAnimator = null
        val panelView = view.findViewById<View>(R.id.panel)
        panelView.scaleX = 1f
        panelView.scaleY = 1f
        panelView.alpha = 1f
        recordingChromeActive = false
        reviewChromeActive = false
        val stripWidth = playbackStripWidthPx()
        val safe = safeBounds()
        val saved = overlayPositions?.get(positionKey(PlacementKind.PLAYING))
        val centered = OverlayPoint(
            safe.left + ((safe.width - stripWidth) / 2).coerceAtLeast(0),
            safe.top + dp(8)
        )
        val point = pointOverride ?: pendingPlaybackPoint ?: saved ?: centered
        pendingPlaybackPoint = null
        applyWindowPoint(
            params,
            OverlayPlacement.clamp(point.x, point.y, stripWidth, playbackStripHeightPx(), safe, dp(8)),
            PlacementKind.PLAYING,
            save = false
        )
        val panel = view.findViewById<LinearLayout>(R.id.panel)
        panel.visibility = View.VISIBLE
        panel.background = null
        panel.setPadding(0, 0, 0, 0)
        view.findViewById<View>(R.id.bubble).visibility = View.GONE
        view.findViewById<View>(R.id.controllerHeader).visibility = View.GONE
        view.findViewById<View>(R.id.tvStatus).visibility = View.GONE
        view.findViewById<View>(R.id.settingsHost).visibility = View.GONE
        view.findViewById<View>(R.id.readyFooter).visibility = View.GONE
        view.findViewById<View>(R.id.saveBox).visibility = View.GONE
        view.findViewById<View>(R.id.savedBox).visibility = View.GONE
        view.findViewById<View>(R.id.discardedBox).visibility = View.GONE
        (view.findViewById<View>(R.id.contentFrame)?.layoutParams as? LinearLayout.LayoutParams)
            ?.topMargin = 0
        applyPlaybackChrome()
        startPlaybackTicker()
        view.requestLayout()
        view.post {
            clampControllerToScreen()
            refreshControllerBoundsSnapshot()
        }
    }

    private fun applyPlaybackChrome() {
        val view = controllerView ?: return
        val panel = view.findViewById<LinearLayout>(R.id.panel)
        val stripWidth = playbackStripWidthPx()
        panel.visibility = View.VISIBLE
        view.findViewById<View>(R.id.bubble).visibility = View.GONE
        (view.findViewById<View>(R.id.contentFrame)?.layoutParams as? LinearLayout.LayoutParams)
            ?.topMargin = 0
        view.findViewById<View>(R.id.playingPanel)?.layoutParams?.apply {
            height = LinearLayout.LayoutParams.MATCH_PARENT
        }
        panel.layoutParams = panel.layoutParams.apply {
            width = if (playbackMinimized) {
                LinearLayout.LayoutParams.WRAP_CONTENT
            } else {
                stripWidth
            }
            height = if (playbackMinimized) {
                LinearLayout.LayoutParams.WRAP_CONTENT
            } else {
                playbackStripHeightPx()
            }
        }
        view.findViewById<View>(R.id.contentFrame).visibility =
            if (playbackMinimized) View.GONE else View.VISIBLE
        val recovery = BotState.recoveryStrip.value
        view.findViewById<View>(R.id.playingPanel).visibility =
            if (playbackMinimized || recovery != null) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.recoveryStripPanel).visibility =
            if (!playbackMinimized && recovery != null) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.tvRecoveryLabel)?.text =
            recovery?.let { "${it.label}… try ${it.tryIndex} of ${it.tryCount}" } ?: ""
        view.findViewById<View>(R.id.playPill).visibility =
            if (playbackMinimized) View.VISIBLE else View.GONE
        val narrow = stripWidth < dp(350)
        view.findViewById<TextView>(R.id.tvPlayingName)?.textSize =
            if (narrow) 12.5f else 13f
        view.findViewById<TextView>(R.id.tvPlayingRun)?.textSize =
            if (narrow) 10f else 10.5f
        view.findViewById<TextView>(R.id.tvPlayElapsed)?.textSize =
            if (narrow) 10.5f else 11f
        val gap = dp(if (narrow) 6 else 8)
        view.findViewById<View>(R.id.btnPlayPause)?.layoutParams =
            (view.findViewById<View>(R.id.btnPlayPause)?.layoutParams as? LinearLayout.LayoutParams)?.apply { marginStart = gap }
        view.findViewById<View>(R.id.btnStopPlayback)?.layoutParams =
            (view.findViewById<View>(R.id.btnStopPlayback)?.layoutParams as? LinearLayout.LayoutParams)?.apply { marginStart = gap }
        view.findViewById<View>(R.id.btnPlayMinimize)?.layoutParams =
            (view.findViewById<View>(R.id.btnPlayMinimize)?.layoutParams as? LinearLayout.LayoutParams)?.apply { marginStart = gap }
        controllerParams?.let { params ->
            if (params.width != WindowManager.LayoutParams.WRAP_CONTENT ||
                params.height != WindowManager.LayoutParams.WRAP_CONTENT
            ) {
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                    // Window detached.
                }
            }
        }
        view.requestLayout()
        view.post {
            clampControllerToScreen()
            refreshControllerBoundsSnapshot()
        }
    }

    private fun minimizePlaybackStrip() {
        if (!playbackChromeActive) return
        playbackMinimized = true
        applyPlaybackChrome()
    }

    private fun expandPlaybackStrip() {
        if (!playbackChromeActive) return
        playbackMinimized = false
        applyPlaybackChrome()
    }

    private fun startPlaybackTicker() {
        handler.removeCallbacks(playbackTicker)
        handler.post(playbackTicker)
    }

    private fun stopPlaybackTicker() {
        handler.removeCallbacks(playbackTicker)
    }

    private val playbackTicker = object : Runnable {
        override fun run() {
            if (!playbackChromeActive) return
            val view = controllerView ?: return
            applyPlaybackTimerState(view)
            val macro = macros.firstOrNull { it.id == BotState.activeMacroId.value }
            if (macro != null) {
                view.findViewById<TextView>(R.id.tvPlayPillName)?.text = macro.name
            }
            handler.postDelayed(this, 500L)
        }
    }

    private fun applyPlaybackTimerState(view: View) {
        val countdown = BotState.startCountdown.value
        val countingDown = countdown != null
        val timerText = if (countingDown) "${countdown}s" else {
            formatClock(player?.runningElapsedMs() ?: 0L)
        }
        view.findViewById<TextView>(R.id.tvPlayElapsed)?.apply {
            text = timerText
            setTextColor(if (countingDown) Ds.WARN else 0xFFEAF1FF.toInt())
            contentDescription = if (countingDown) {
                "Starting in $countdown seconds"
            } else {
                "Playback time"
            }
        }
        view.findViewById<TextView>(R.id.tvPlayPillTimer)?.text = timerText
        val runLabel = BotState.playbackRunLabel.value
        view.findViewById<TextView>(R.id.tvPlayingRun)?.apply {
            visibility = if (countingDown || runLabel.isNullOrBlank()) {
                View.GONE
            } else {
                View.VISIBLE
            }
            text = runLabel ?: ""
        }
        view.findViewById<ImageButton>(R.id.btnPlayPause)?.apply {
            isEnabled = !countingDown
            alpha = if (countingDown) 0.45f else 1f
            contentDescription = if (countingDown) {
                "Pause becomes available after the countdown"
            } else {
                "Pause playback"
            }
        }
    }

    private fun applyPlaybackInfo(view: View) {
        val macro = macros.firstOrNull { it.id == BotState.activeMacroId.value }
        view.findViewById<TextView>(R.id.tvPlayingName)?.text =
            macro?.name ?: BotState.activeMacroName.value ?: "Macro"
        view.findViewById<TextView>(R.id.tvPlayPillName)?.text =
            macro?.name ?: BotState.activeMacroName.value ?: "Macro"
        applyPlaybackTimerState(view)
        view.findViewById<ImageButton>(R.id.btnPlayPause)?.apply {
            setImageResource(
                if (BotState.state.value == ControllerState.PLAYBACK_PAUSED) {
                    R.drawable.ic_play_small
                } else {
                    R.drawable.ic_pause
                }
            )
            if (BotState.startCountdown.value == null) {
                contentDescription =
                    if (BotState.state.value == ControllerState.PLAYBACK_PAUSED) {
                        "Resume playback"
                    } else {
                        "Pause playback"
                    }
            }
        }
    }

    private fun enterRecordingChrome() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        recordingChromeActive = true
        recordingMinimized = settingsStore.settings.value.recordingStartMinimized
        lastRecordingMinimized = recordingMinimized
        val safe = safeBounds()
        val barWidth = recordingStripWidthPx()
        val saved = overlayPositions?.get(positionKey(PlacementKind.RECORDING))
        val point = saved ?: OverlayPoint(
            safe.left + ((safe.width - barWidth) / 2).coerceAtLeast(0),
            safe.top + dp(8)
        )
        val clamped = OverlayPlacement.clamp(
            point.x,
            point.y,
            barWidth,
            dp(REC_STRIP_HEIGHT),
            safe,
            dp(8)
        )
        applyWindowPoint(params, clamped, PlacementKind.RECORDING, save = false)
        val pad = dp(10)
        controllerBoundsSnapshot = ScreenRect(
            (clamped.x - pad).toFloat(),
            (clamped.y - pad).toFloat(),
            (clamped.x + barWidth + pad).toFloat(),
            (clamped.y + dp(REC_STRIP_HEIGHT) + pad).toFloat()
        )
        applyRecordingChrome()
    }

    private fun applyRecordingChrome() {
        val view = controllerView ?: return
        val panel = view.findViewById<LinearLayout>(R.id.panel) ?: return
        view.findViewById<View>(R.id.controllerHeader).visibility = View.GONE
        view.findViewById<View>(R.id.tvStatus).visibility = View.GONE
        view.findViewById<View>(R.id.settingsHost).visibility = View.GONE
        // Hide the controller pages in the same frame the bar appears so the
        // two never draw on top of each other.
        view.findViewById<View>(R.id.readyPanel).visibility = View.GONE
        view.findViewById<View>(R.id.reviewPanel).visibility = View.GONE
        view.findViewById<View>(R.id.playingPanel).visibility = View.GONE
        view.findViewById<View>(R.id.statsPanel).visibility = View.GONE
        view.findViewById<View>(R.id.readyFooter).visibility = View.GONE
        panel.background = null
        panel.setPadding(0, 0, 0, 0)
        val content = view.findViewById<View>(R.id.contentFrame)
        (content?.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        content?.visibility = if (recordingMinimized) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.recPill).visibility =
            if (recordingMinimized) View.VISIBLE else View.GONE
        panel.layoutParams = panel.layoutParams.apply {
            width = if (recordingMinimized) {
                LinearLayout.LayoutParams.WRAP_CONTENT
            } else {
                recordingStripWidthPx()
            }
            height = if (recordingMinimized) {
                LinearLayout.LayoutParams.WRAP_CONTENT
            } else {
                dp(REC_STRIP_HEIGHT)
            }
        }
        if (!recordingMinimized) {
            applyRecordingStripLayout(recordingStripWidthPx())
        }
        panel.requestLayout()
        view.requestLayout()
        view.post {
            refreshRecordingBounds()
            clampControllerToScreen()
        }
    }

    private fun restoreControllerChrome() {
        dismissRecConfirm()
        if (!recordingChromeActive && !reviewChromeActive && !playbackChromeActive) {
            return
        }
        val view = controllerView ?: return
        val panel = view.findViewById<LinearLayout>(R.id.panel) ?: return
        recordingChromeActive = false
        reviewChromeActive = false
        playbackChromeActive = false
        playbackMinimized = false
        pendingPlaybackPoint = null
        stopPlaybackTicker()
        setSaveTyping(false)
        view.findViewById<View>(R.id.saveBox).visibility = View.GONE
        view.findViewById<View>(R.id.savedBox).visibility = View.GONE
        view.findViewById<View>(R.id.discardedBox).visibility = View.GONE
        recordingMinimized = false
        lastRecordingMinimized = false
        panel.background = getDrawable(R.drawable.bg_panel)
        val inset = dp(10)
        panel.setPadding(inset, inset, inset, inset)
        val content = view.findViewById<View>(R.id.contentFrame)
        (content?.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4)
        content?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.controllerHeader).visibility = View.VISIBLE
        view.findViewById<View>(R.id.recPill).visibility = View.GONE
        view.findViewById<View>(R.id.playPill).visibility = View.GONE
        view.findViewById<View>(R.id.playingPanel).visibility = View.GONE
        view.findViewById<View>(R.id.recoveryStripPanel).visibility = View.GONE
        applyPanelWidth(settingsView != null)
        view.requestLayout()
        view.post {
            val params = controllerParams
            if (params != null && !collapsed) {
                val panelWidth = panel.width.takeIf { it > 0 }
                    ?: controllerPanelWidthPx()
                val panelHeight = panel.height.takeIf { it > 0 } ?: dp(260)
                val point = savedPosition(PlacementKind.CONTROLLER)
                    ?: defaultControllerPoint(panelWidth, panelHeight)
                applyWindowPoint(
                    params,
                    OverlayPlacement.clamp(
                        point.x,
                        point.y,
                        panelWidth,
                        panelHeight,
                        safeBounds(),
                            dp(8)
                    ),
                    PlacementKind.CONTROLLER,
                    save = false
                )
            }
            refreshControllerBoundsSnapshot()
        }
    }

    private fun minimizeRecordingBar() {
        if (!recordingUiActive()) return
        dismissRecConfirm()
        recordingMinimized = true
        refreshUi()
    }

    private fun expandRecordingBar() {
        if (!recordingUiActive()) return
        recordingMinimized = false
        refreshUi()
    }

    private fun requestRecordingRestart() {
        if (!recordingUiActive()) return
        if (recorded.isEmpty()) {
            restartRecordingNow()
            return
        }
        showRecConfirm(RecConfirmAction.RESTART)
    }

    private fun requestRecordingCancel() {
        if (!recordingUiActive()) return
        if (recorded.isEmpty()) {
            discardRecording()
            return
        }
        showRecConfirm(RecConfirmAction.CANCEL)
    }

    private fun showRecConfirm(action: RecConfirmAction) {
        val view = controllerView ?: return
        pendingRecAction = action
        dismissConfirmTimer()
        view.findViewById<View>(R.id.recordingStrip).visibility = View.GONE
        view.findViewById<View>(R.id.recordingConfirm).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.tvRecConfirm).text = "Discard this recording?"
        val revert = Runnable {
            if (pendingRecAction != null) dismissRecConfirm()
        }
        confirmRevert = revert
        handler.postDelayed(revert, REC_CONFIRM_MS)
        refreshRecordingBounds()
    }

    private fun dismissRecConfirm() {
        dismissConfirmTimer()
        pendingRecAction = null
        val view = controllerView ?: return
        view.findViewById<View>(R.id.recordingConfirm).visibility = View.GONE
        view.findViewById<View>(R.id.recordingStrip).visibility = View.VISIBLE
        view.post { refreshRecordingBounds() }
    }

    private fun dismissConfirmTimer() {
        confirmRevert?.let { handler.removeCallbacks(it) }
        confirmRevert = null
    }

    private fun confirmRecAction() {
        val action = pendingRecAction
        dismissRecConfirm()
        when (action) {
            RecConfirmAction.RESTART -> restartRecordingNow()
            RecConfirmAction.CANCEL -> discardRecording()
            null -> Unit
        }
    }

    private fun restartRecordingNow() {
        if (!recording && !startingPassive) return
        recorded.clear()
        pathPoints.clear()
        capturedGestures = 0
        rejectedGestures = 0
        gestureSeq = 0
        pausedAccumMs = 0L
        val now = SystemClock.uptimeMillis()
        recordStartMs = now
        pausedAtMs = if (BotState.state.value == ControllerState.RECORDING_PAUSED) now else 0L
        downTime = 0L
        lastStatusMs = 0L
        lastNotificationMs = 0L
        lastPulseCount = -1
        BotState.clearRecording()
        BotState.setRecordedCount(0)
        updateRecordingInfo()
        updateRecordingNotification()
        refreshUi()
    }

    private fun updateTouchCounter(animate: Boolean) {
        val view = controllerView ?: return
        val count = if (recordingUiActive()) BotState.recordedGestureCount.value else recorded.size
        val label = count.toString()
        view.findViewById<TextView>(R.id.tvRecCount)?.text = label
        view.findViewById<TextView>(R.id.tvRecPillCount)?.text = label
        if (animate && count != lastPulseCount) {
            view.findViewById<View>(R.id.touchCounter)?.let { chip ->
                chip.animate().cancel()
                chip.animate().scaleX(1.12f).scaleY(1.12f).setDuration(60L)
                    .withEndAction {
                        chip.animate().scaleX(1f).scaleY(1f).setDuration(60L).start()
                    }
                    .start()
            }
        }
        if (count != lastPulseCount) lastPulseCount = count
    }

    private fun recordingStripWidthPx(): Int {
        val landscape = screenWidth > screenHeight
        val base = if (landscape) REC_STRIP_LANDSCAPE_W else REC_STRIP_PORTRAIT_W
        val safe = safeBounds()
        return minOf(dp(base), (safe.width - dp(16)).coerceAtLeast(dp(240)))
    }

    private fun applyRecordingStripLayout(widthPx: Int) {
        val view = controllerView ?: return
        val strip = view.findViewById<View>(R.id.recordingStrip) ?: return
        val finish = view.findViewById<View>(R.id.btnRecFinish) ?: return
        val label = view.findViewById<TextView>(R.id.tvRecFinishLabel)
        val timer = view.findViewById<TextView>(R.id.tvRecTimer)
        val count = view.findViewById<TextView>(R.id.tvRecCount)
        val restart = view.findViewById<View>(R.id.btnRecRestart)
        val cancel = view.findViewById<View>(R.id.btnRecCancel)
        val pause = view.findViewById<View>(R.id.btnRecPause)
        val minimize = view.findViewById<View>(R.id.btnRecMinimize)
        val narrow = widthPx < dp(
            REC_STRIP_NARROW_BELOW +
                (((resources.configuration.fontScale - 1f).coerceAtLeast(0f)) * 30f).roundToInt()
        )
        label?.visibility = if (narrow) View.GONE else View.VISIBLE
        finish.layoutParams = finish.layoutParams.apply {
            width = if (narrow) dp(32) else dp(88)
            height = dp(32)
        }
        val finishPad = dp(if (narrow) 8 else 9)
        finish.setPadding(finishPad, 0, dp(if (narrow) 8 else 12), 0)
        val smallMargin = dp(if (narrow) 0 else 2)
        val mediumMargin = dp(if (narrow) 2 else 4)
        (restart.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = smallMargin
        (cancel.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = smallMargin
        (pause.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = mediumMargin
        (finish.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = mediumMargin
        (minimize.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
        strip.setPadding(
            dp(if (narrow) 10 else 14),
            0,
            dp(if (narrow) 4 else 6),
            0
        )
        timer?.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            if (narrow) 13.5f else 15f
        )
        count?.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            if (narrow) 11f else 12f
        )
        label?.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            12.5f
        )
        view.findViewById<TextView>(R.id.tvRecConfirm)?.let { confirm ->
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                confirm,
                9,
                13,
                1,
                android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }
        controllerView?.findViewById<View>(R.id.recordingPanel)?.let { panel ->
            panel.layoutParams = panel.layoutParams.apply {
                width = widthPx
                height = dp(REC_STRIP_HEIGHT)
            }
        }
        view.requestLayout()
        view.post { refreshRecordingBounds() }
    }

    private fun refreshRecordingBounds() {
        val view = controllerView ?: return
        val target = if (recordingMinimized) {
            view.findViewById<View>(R.id.recPill)
        } else {
            view.findViewById<View>(R.id.recordingPanel)
        } ?: return
        if (target.width <= 0 || target.height <= 0) return
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val pad = dp(10)
        controllerBoundsSnapshot = ScreenRect(
            (location[0] - pad).toFloat(),
            (location[1] - pad).toFloat(),
            (location[0] + target.width + pad).toFloat(),
            (location[1] + target.height + pad).toFloat()
        )
    }

    private fun refreshUi() {
        val view = controllerView ?: return
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

        val showRecording = state == ControllerState.RECORDING ||
            state == ControllerState.RECORDING_PAUSED ||
            startingPassive
        val showReview = state == ControllerState.REVIEW
        val showPlaying = state == ControllerState.PLAYING || state == ControllerState.PLAYBACK_PAUSED
        val showReady = !showRecording && !showReview && !showPlaying

        if (showRecording) {
            if (!recordingChromeActive) {
                collapsed = false
                enterRecordingChrome()
            } else if (recordingMinimized != lastRecordingMinimized) {
                lastRecordingMinimized = recordingMinimized
                applyRecordingChrome()
            }
        } else if (showReview) {
            if (!reviewChromeActive) {
                collapsed = false
                beginSaveToast()
            }
        } else if (showPlaying) {
            if (!playbackChromeActive) {
                collapsed = false
                enterPlaybackChrome()
            }
        } else if (recordingChromeActive || reviewChromeActive || playbackChromeActive) {
            restoreControllerChrome()
        }

        val recovery = BotState.recoveryStrip.value
        val statsVisible = page == PAGE_STATS &&
            !showRecording && !showReview && !showPlaying
        val wallsVisible = page == PAGE_WALLS &&
            !showRecording && !showReview && !showPlaying
        val startCountdown = BotState.startCountdown.value
        val settingsOpen = settingsView != null
        val readyVisible = !statsVisible && !wallsVisible &&
            showReady && !settingsOpen
        view.findViewById<View>(R.id.readyFooter).visibility =
            if (readyVisible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.statsPanel).visibility =
            if (statsVisible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.wallsPanel).visibility =
            if (wallsVisible && !settingsOpen) View.VISIBLE else View.GONE
        if (wallsVisible && !settingsOpen) refreshAutoWalls()
        view.findViewById<View>(R.id.readyPanel).visibility =
            if (!statsVisible && !wallsVisible && showReady) {
                View.VISIBLE
            } else {
                View.GONE
            }
        view.findViewById<View>(R.id.recordingPanel).visibility =
            if (!statsVisible && showRecording && !recordingMinimized) {
                View.VISIBLE
            } else {
                View.GONE
            }
        view.findViewById<View>(R.id.reviewPanel).visibility =
            if (!statsVisible && showReview) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.playingPanel).visibility =
            if (!statsVisible && showPlaying && recovery == null) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.recoveryStripPanel).visibility =
            if (playbackChromeActive && !playbackMinimized && recovery != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
        view.findViewById<TextView>(R.id.tvRecoveryLabel)?.text =
            recovery?.let { "${it.label}… try ${it.tryIndex} of ${it.tryCount}" } ?: ""
        if (reviewChromeActive) {
            view.findViewById<View>(R.id.controllerHeader).visibility = View.GONE
            view.findViewById<View>(R.id.contentFrame).visibility = View.GONE
            view.findViewById<View>(R.id.readyFooter).visibility = View.GONE
            view.findViewById<View>(R.id.recordingPanel).visibility = View.GONE
            view.findViewById<View>(R.id.reviewPanel).visibility = View.GONE
            view.findViewById<View>(R.id.playingPanel).visibility = View.GONE
            view.findViewById<View>(R.id.statsPanel).visibility = View.GONE
            view.findViewById<View>(R.id.recPill).visibility = View.GONE
            view.findViewById<View>(R.id.playPill).visibility = View.GONE
            view.findViewById<View>(R.id.tvStatus).visibility = View.GONE
            applyReviewChrome()
        }
        val macrosTab = !statsVisible && !wallsVisible
        view.findViewById<TextView>(R.id.btnPageController).apply {
            background = Ds.rounded(
                if (macrosTab) Ds.ACCENT else android.graphics.Color.TRANSPARENT,
                8,
                this@FloatingMenuService
            )
            setTextColor(if (macrosTab) android.graphics.Color.WHITE else Ds.MUTED)
        }
        view.findViewById<TextView>(R.id.btnPageStats).apply {
            background = Ds.rounded(
                if (statsVisible) Ds.ACCENT else android.graphics.Color.TRANSPARENT,
                8,
                this@FloatingMenuService
            )
            setTextColor(if (statsVisible) android.graphics.Color.WHITE else Ds.MUTED)
        }
        view.findViewById<TextView>(R.id.btnPageWalls).apply {
            background = Ds.rounded(
                if (wallsVisible) Ds.ACCENT else android.graphics.Color.TRANSPARENT,
                8,
                this@FloatingMenuService
            )
            setTextColor(if (wallsVisible) android.graphics.Color.WHITE else Ds.MUTED)
        }
        val dotColor = when {
            showRecording -> Ds.RECORD
            state == ControllerState.PLAYBACK_PAUSED -> Ds.WARN
            else -> Ds.PLAY
        }
        view.findViewById<View>(R.id.statusDot)?.backgroundTintList =
            ColorStateList.valueOf(dotColor)
        applyBubbleChrome(view)
        val statusStripVisible = preparingPassive || (!showRecording &&
            !playbackChromeActive && !recordingChromeActive && !reviewChromeActive &&
            state != ControllerState.READY &&
            state != ControllerState.INACTIVE &&
            state != ControllerState.STOPPING)
        view.findViewById<TextView>(R.id.tvStatus)?.apply {
            visibility = if (statusStripVisible) View.VISIBLE else View.GONE
            text = BotState.status.value
        }

        view.findViewById<Button>(R.id.btnRecord).isEnabled =
            !startingPassive && !preparingPassive
        updateWatchActions()

        val recordMode = activeRecordingMode ?: settingsStore.settings.value.recordingMode
        val passiveMode = recordMode == RecordingMode.PASSIVE
        view.findViewById<TextView>(R.id.tvRecordMode).apply {
            text = when {
                preparingPassive -> "Starting capture…"
                passiveMode -> "Passive · game responds"
                else -> "Capture only · blocks game input"
            }
            setTextColor(
                if (preparingPassive) Ds.MUTED
                else if (passiveMode) 0xFF22C55E.toInt() else 0xFFF59E0B.toInt()
            )
        }
        view.findViewById<TextView>(R.id.tvRecState).text = when {
            startingPassive -> "STARTING"
            state == ControllerState.RECORDING_PAUSED -> "PAUSED"
            else -> "REC"
        }
        view.findViewById<ImageButton>(R.id.btnRecPause).apply {
            isEnabled = !startingPassive
            setImageResource(
                if (state == ControllerState.RECORDING_PAUSED) {
                    R.drawable.ic_play_small
                } else {
                    R.drawable.ic_pause
                }
            )
            contentDescription =
                if (state == ControllerState.RECORDING_PAUSED) "Resume recording" else "Pause recording"
        }
        view.findViewById<View>(R.id.btnRecFinish).isEnabled = !startingPassive
        view.findViewById<TextView>(R.id.tvRecFinishLabel).text = "Finish"
        updateTouchCounter(animate = false)
        val timerText = if (startingPassive) "00:00" else formatClock(elapsedRecordingMs())
        view.findViewById<TextView>(R.id.tvRecTimer).text = timerText
        view.findViewById<TextView>(R.id.tvRecPillTimer).text = timerText

        view.findViewById<TextView>(R.id.tvReviewInfo).text =
            "${Plural.of(count, "gesture")} · ${formatDuration(MacroTimeline.totalDuration(recorded))}"

        view.findViewById<TextView>(R.id.tvPlayingName).text =
            BotState.activeMacroName.value ?: "Macro"
        if (playbackChromeActive) applyPlaybackInfo(view)
        val countdown = BotState.repeatCountdown.value
        val bubbleStatus = view.findViewById<TextView>(R.id.bubbleStatus)
        bubbleStatus.text = when {
            showRecording -> ""
            manualWatch -> "WATCH"
            state == ControllerState.PLAYBACK_PAUSED -> "II"
            state == ControllerState.PLAYING && startCountdown != null -> "${startCountdown}s"
            state == ControllerState.PLAYING && countdown != null -> "${countdown}s"
            state == ControllerState.PLAYING -> "PLAY"
            else -> ""
        }

        if (collapsed && !showRecording) {
            view.findViewById<View>(R.id.panel).visibility = View.GONE
            view.findViewById<View>(R.id.bubble).visibility = View.VISIBLE
        }
        scheduleTouchTargets()
    }

    private fun updateRecordingInfo() {
        val view = controllerView ?: return
        val count = BotState.recordedGestureCount.value
        view.findViewById<TextView>(R.id.tvReviewInfo).text =
            "${Plural.of(count, "gesture")} · ${formatDuration(MacroTimeline.totalDuration(recorded))}"
        view.findViewById<TextView>(R.id.tvRecTimer).text =
            if (startingPassive) "00:00" else formatClock(elapsedRecordingMs())
        updateTouchCounter(animate = true)
    }

    private fun onStatusChanged(status: String) {
        val view = controllerView ?: return
        view.findViewById<TextView>(R.id.tvStatus).text = status
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
                    resources.getQuantityString(
                        if (activeRecordingMode == RecordingMode.PASSIVE) {
                            R.plurals.notification_recording_passive
                        } else {
                            R.plurals.notification_recording_capture
                        },
                        count,
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
            preparingPassive ||
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

    private fun showConfirmDialog(
        title: String,
        message: String,
        positive: String,
        onConfirm: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ds.rounded(Ds.PANEL, 16, this@FloatingMenuService, Ds.PANEL_BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(Ds.text(uiContext, 15f, Ds.TEXT, bold = true).apply { text = title })
        card.addView(Ds.text(uiContext, 12f, Ds.MUTED).apply {
            text = message
            setPadding(0, dp(6), 0, dp(12))
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun button(label: String, primary: Boolean, action: () -> Unit): Button =
            Button(uiContext).apply {
                text = label
                isAllCaps = false
                textSize = 12.5f
                minWidth = 0
                stateListAnimator = null
                setTextColor(Color.WHITE)
                background = Ds.rounded(
                    if (primary) Ds.DANGER_OUTLINE else Ds.BUTTON,
                    9,
                    this@FloatingMenuService
                )
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginStart = if (primary) dp(8) else 0
                }
                setOnClickListener {
                    haptic()
                    dismissInlineCard()
                    action()
                }
            }
        buttons.addView(button("Cancel", false) {})
        buttons.addView(button(positive, true, onConfirm))
        card.addView(buttons)
        showInlineCard(card)
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
        val resetIntent = PendingIntent.getService(
            this, 2,
            Intent(this, FloatingMenuService::class.java).setAction(ACTION_RESET_POSITION),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.action_reset_position), resetIntent)
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
        cancelPassivePrepare("closed")
        cancelAutoOpen()
        GameSessionTracker.closeSessionAsync()
        stopPlayback()
        cancelAutoOpen()
        stopRecording(silent = true)
        stopPassiveRecorder()
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

    private fun controllerPanelWidthPx(): Int {
        val landscape = screenWidth > screenHeight
        val baseDp = if (landscape) PANEL_LANDSCAPE_W else PANEL_PORTRAIT_W
        return minOf(dp(baseDp), (screenWidth * WIDTH_CAP).toInt())
    }

    private fun controllerPanelHeightPx(): Int {
        val landscape = screenWidth > screenHeight
        val baseDp = if (landscape) PANEL_LANDSCAPE_H else PANEL_PORTRAIT_H
        return minOf(dp(baseDp), (screenHeight * HEIGHT_CAP).toInt())
    }

    private fun applyPanelWidth(@Suppress("UNUSED_PARAMETER") settings: Boolean) {
        val view = controllerView ?: return
        val panel = view.findViewById<LinearLayout>(R.id.panel) ?: return
        val targetWidth = controllerPanelWidthPx()
        val targetHeight = controllerPanelHeightPx()
        AppEventLog.log(
            "panel size screen=${screenWidth}x${screenHeight} " +
                "density=${uiContext.resources.displayMetrics.density} " +
                "target=${targetWidth}x$targetHeight"
        )
        var changed = false
        if (panel.layoutParams.width != targetWidth ||
            panel.layoutParams.height != targetHeight
        ) {
            panel.layoutParams = panel.layoutParams.apply {
                width = targetWidth
                height = targetHeight
            }
            changed = true
        }
        controllerParams?.let { params ->
            if (params.width != WindowManager.LayoutParams.WRAP_CONTENT ||
                params.height != WindowManager.LayoutParams.WRAP_CONTENT
            ) {
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                    // Window detached.
                }
            }
        }
        if (changed) {
            panel.requestLayout()
            view.requestLayout()
            view.post {
                clampControllerToScreen()
                refreshControllerBoundsSnapshot()
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * uiContext.resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int =
        (value * uiContext.resources.displayMetrics.density).toInt()

    private fun scheduleTouchTargets() {
        handler.removeCallbacks(touchTargetsRunnable)
        handler.postDelayed(touchTargetsRunnable, 80L)
    }

    private fun installTouchTargetsNow() {
        val view = controllerView ?: return
        val minPx = (MIN_TOUCH_DP * resources.displayMetrics.density).roundToInt()
        TouchTargets.install(view, minPx)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------- walls & recovery

    private fun startRecoveryWatchdog(target: MacroPlayer) {
        recoveryWatchdog?.stop()
        val triggerRepository = com.clahbot.triggers.TriggerRepository.get(this)
        val triggerRuntime = com.clahbot.triggers.TriggerRuntime(
            repository = triggerRepository,
            stopAutomation = { target.stop() }
        )
        val watchdog = RecoveryWatchdog(
            scope = scope,
            context = this,
            engine = gameEngine,
            reader = resultsReader,
            captureBusy = { resultGate.isHeld() },
            onTriggerTick = { reading ->
                triggerRuntime.evaluateAndRun(
                    frame = reading.frame,
                    observation = reading.observation,
                    playerRunning = target.isRunning()
                )
            }
        ) { reason -> abortPlaybackAfterRecovery(reason) }
        recoveryWatchdog = watchdog
        watchdog.start(target)
    }

    private fun abortPlaybackAfterRecovery(reason: String) {
        AppEventLog.log("playback aborted by recovery: $reason")
        BotState.setInterrupted(true)
        val wasRun = playbackRunActive
        player?.stop()
        player = null
        endAttackSession(interrupted = true)
        if (wasRun) onPlaybackRunEnded()
        updateNotification(getString(R.string.notification_idle))
        refreshUi()
    }

    private fun applyResultToast(ui: ResultToastUi?) {
        val view = controllerView ?: return
        val box = view.findViewById<View>(R.id.resultToastBox) ?: return
        resultToastHide?.let(handler::removeCallbacks)
        resultToastHide = null
        if (ui == null || playbackChromeActive || recordingChromeActive || reviewChromeActive) {
            box.visibility = View.GONE
            return
        }
        box.background = getDrawable(
            if (ui.kind == ResultToastUi.Kind.SUCCESS) R.drawable.bg_toast_ok
            else R.drawable.bg_toast_warn
        )
        view.findViewById<TextView>(R.id.tvResultToast)?.text = ui.text
        box.visibility = View.VISIBLE
        val hide = Runnable {
            box.visibility = View.GONE
            BotState.clearResultToast(ui.id)
        }
        resultToastHide = hide
        handler.postDelayed(hide, RESULT_TOAST_MS)
    }

    private fun ensureAutoWallsRuntime(): AutoWallsRuntime {
        autoWallsRuntime?.let { return it }
        val session = AutoWallsSession(AutoWallsRepository.get(this))
        val device = AutoWallsDevice(gameEngine, resultsReader)
        return AutoWallsRuntime(
            session = session,
            device = device,
            log = { message -> AppEventLog.log(message) }
        ).also { autoWallsRuntime = it }
    }

    /**
     * Feed one genuinely completed attack (a saved results record) into the Auto
     * Walls 2.0 trigger counter. Writes only auto_walls_trigger.json — no gesture,
     * no upgrade — and is skipped while Auto Walls 2.0 is switched off.
     */
    private fun noteAutoWallsAttack() {
        if (!AutoWallsRepository.get(this).loadConfig().enabled) return
        ensureAutoWallsRuntime().onAttackCompleted()
    }

    private fun syncMacroLists() {
        macros = repository.macros.value.filter { it.kind != Macro.KIND_WALL }
    }

    private fun haptic() {
        if (!settingsStore.settings.value.hapticFeedback) return
        Haptics.tap(controllerView)
    }

    private fun hapticEvent() {
        if (!settingsStore.settings.value.hapticFeedback) return
        Haptics.event(this, settingsStore.settings.value.hapticIntensity)
    }

    private fun toast(message: String) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // Toast can fail if the app is going away.
        }
    }

    companion object {
        private const val PANEL_LANDSCAPE_W = 340
        private const val PANEL_LANDSCAPE_H = 300
        private const val PANEL_PORTRAIT_W = 320
        private const val PANEL_PORTRAIT_H = 440
        private const val WIDTH_CAP = 0.94f
        private const val HEIGHT_CAP = 0.86f
        private const val REC_STRIP_LANDSCAPE_W = 368
        private const val REC_STRIP_PORTRAIT_W = 328
        private const val REC_STRIP_NARROW_BELOW = 362
        private const val REC_STRIP_HEIGHT = 44
        private const val REC_CONFIRM_MS = 3_000L
        private const val SAVED_UNDO_MS = 2_000L
        private const val PLAY_STRIP_LANDSCAPE_W = 368
        private const val PLAY_STRIP_PORTRAIT_W = 328
        private const val PLAY_STRIP_HEIGHT = 52
        private const val PAGE_CONTROLLER = 0
        private const val PAGE_STATS = 1
        private const val PAGE_WALLS = 2
        private const val STATS_SESSION = 0
        private const val STATS_ALL = 1
        private const val RESULT_TOAST_MS = 5_000L
        private const val TAG = "AutoClashController"
        private const val AUTO_RESTART_DELAY_MS = 5_000L
        private const val CHANNEL_ID = "autoclash_controller"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_THROTTLE_MS = 900L
        private const val STATUS_THROTTLE_MS = 500L
        private const val UNDO_WINDOW_MS = 5_000L
        private const val BUBBLE_SIZE_DP = 52
        private const val MIN_TOUCH_DP = 40
        private const val BUBBLE_MARGIN_DP = 8
        private const val BUBBLE_TOP_DP = 48
        private const val BUBBLE_TOP_LANDSCAPE_DP = 8
        private const val SNAP_DURATION_MS = 180L
        private const val COLLAPSE_MS = 170L
        private const val AUTO_OPEN_MOVE_MS = 220L
        private const val AUTO_OPEN_FADE_MS = 130L
        private const val AUTO_OPEN_TIMEOUT_MS = 15_000L
        private const val AUTO_OPEN_NOTIFICATION_ID = 1002
        private const val EXTRA_MACRO_ID = "macro_id"

        const val ACTION_PLAY = "com.clahbot.action.PLAY"
        const val ACTION_OPEN = "com.clahbot.action.OPEN"
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
