package com.clahbot.service

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
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clahbot.R
import android.content.res.ColorStateList
import com.clahbot.data.AttackRepository
import com.clahbot.data.BotState
import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.AttackStats
import com.clahbot.data.models.AttackSummary
import com.clahbot.data.ControllerState
import com.clahbot.data.MacroRepository
import com.clahbot.data.MacroSelection
import com.clahbot.data.RecordingMode
import com.clahbot.data.SettingsStore
import com.clahbot.data.models.GesturePath
import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.LootInput
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
import com.clahbot.ui.MainActivity
import com.clahbot.ui.SettingsActivity
import com.clahbot.util.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns the floating controller, the passive touch recorder and macro playback.
 *
 * Passive recording reads getevent through Shizuku or root; no AutoClash
 * window is in the touch path. Capture-only mode still uses a full-screen
 * touch surface and deliberately blocks the game. Playback uses accessibility
 * gesture dispatch in both cases.
 */
class FloatingMenuService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: MacroRepository
    private lateinit var settingsStore: SettingsStore
    private lateinit var attackRepository: AttackRepository
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
    private var lootDialog: Dialog? = null

    @Volatile
    private var controllerBoundsSnapshot: ScreenRect? = null

    private var player: MacroPlayer? = null

    private var macros: List<Macro> = emptyList()
    private var selectedId: Long = -1L
    private var speed: Float = 1f
    private var repeatMode: String = Macro.REPEAT_ONCE

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
    private var session: AttackSession? = null

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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repository = MacroRepository.get(this)
        settingsStore = SettingsStore.get(this)
        attackRepository = AttackRepository.get(this)
        updateScreenSize()
        startForegroundCompat(getString(R.string.notification_idle))

        scope.launch { repository.refresh() }
        scope.launch { attackRepository.refresh() }
        scope.launch { attackRepository.records.collect { renderStats() } }
        scope.launch { repository.macros.collect { onMacrosChanged(it) } }
        scope.launch { BotState.state.collect { refreshUi() } }
        scope.launch { BotState.status.collect { onStatusChanged(it) } }
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
        BotState.setInterrupted(true)
        stopPlayback()
        stopRecording(silent = true)
        stopPassiveRecorder()
        removeIndicator()
        dismissLootDialog()
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
            list.firstOrNull { it.id == reconciled }?.let { macro ->
                speed = MacroTimeline.clampSpeed(macro.speedMultiplier)
                repeatMode = macro.repeatMode
            }
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
        val listDp = (screenHeight / scaledDensity - 320f).coerceIn(48f, 132f)
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

        if (selectedId <= 0) {
            speed = MacroTimeline.clampSpeed(prefs.defaultSpeed)
            repeatMode = prefs.defaultRepeat
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
        view.findViewById<Button>(R.id.btnPlay).setOnClickListener { playSelected() }
        view.findViewById<Button>(R.id.btnPageController).setOnClickListener { setPage(PAGE_CONTROLLER) }
        view.findViewById<Button>(R.id.btnPageStats).setOnClickListener { setPage(PAGE_STATS) }
        view.findViewById<ImageButton>(R.id.btnRename).setOnClickListener { renameSelected() }
        view.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener { deleteSelected() }
        view.findViewById<ImageButton>(R.id.btnSpeedDown).setOnClickListener { changeSpeed(-1) }
        view.findViewById<ImageButton>(R.id.btnSpeedUp).setOnClickListener { changeSpeed(1) }
        view.findViewById<Button>(R.id.btnRepeat).setOnClickListener { cycleRepeat() }

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
        dismissLootDialog()
        if (controllerView == null) ensureController()
        if (controllerView == null) return

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

    private fun playSelected() {
        val macro = macros.firstOrNull { it.id == selectedId }
        if (macro == null) {
            toast("Select a macro first")
            return
        }
        startPlayback(macro)
    }

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
        val adjusted = macro.copy(speedMultiplier = speed, repeatMode = repeatMode)
        if (adjusted.orientation != currentOrientation()) {
            BotState.setStatus("Warning: macro was recorded in ${adjusted.orientation}")
        }

        dismissLootDialog()
        endAttackSession(interrupted = false)
        BotState.setInterrupted(false)
        player?.stop()
        // Place the icon before the first injected gesture can run.
        placeControllerForPlayback(macro)
        val newPlayer = MacroPlayer(screenWidth, screenHeight)
        player = newPlayer
        newPlayer.play(adjusted, scope) {
            endAttackSession(interrupted = false, promptLoot = true)
        }
        if (newPlayer.isRunning()) {
            session = AttackSession(
                startedAt = System.currentTimeMillis(),
                macroId = macro.id,
                macroName = macro.name,
                speed = speed,
                repeat = repeatMode
            )
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
            if (active.isPaused()) getString(R.string.notification_paused)
            else getString(R.string.notification_idle)
        )
        refreshUi()
    }

    private fun stopPlayback() {
        val interrupted = BotState.interrupted.value
        player?.stop()
        player = null
        endAttackSession(interrupted)
        updateNotification(getString(R.string.notification_idle))
        refreshUi()
    }

    private fun setPage(next: Int) {
        page = next
        refreshUi()
        if (next == PAGE_STATS) renderStats()
    }

    private fun endAttackSession(interrupted: Boolean, promptLoot: Boolean = false) {
        val current = session ?: return
        session = null
        val now = System.currentTimeMillis()
        val record = AttackRecord(
            id = nextAttackId(),
            startedAt = current.startedAt,
            endedAt = now,
            durationMs = (now - current.startedAt).coerceAtLeast(0L),
            macroId = current.macroId,
            macroName = current.macroName,
            speedMultiplier = current.speed,
            repeatMode = current.repeat,
            result = if (interrupted) AttackRecord.RESULT_INTERRUPTED else AttackRecord.RESULT_COMPLETED,
            complete = !interrupted,
            // Playback is not an attack until the user confirms the result.
            confirmed = false
        )
        scope.launch {
            val saved = attackRepository.add(record)
            if (saved != null && promptLoot && !interrupted) {
                showLootDialog(saved)
            }
        }
        BotState.setInterrupted(false)
    }

    private fun showLootDialog(record: AttackRecord) {
        dismissLootDialog()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_loot, null)
        view.findViewById<TextView>(R.id.tvLootMessage).text =
            "Macro \"${record.macroName}\" finished. Enter the loot you saw on the " +
                "result screen. Leave a field empty when it was not captured. " +
                "Confirming counts the attack even if every field stays empty."

        fun field(id: Int, initial: Long?) {
            view.findViewById<EditText>(id).setText(initial?.toString() ?: "")
        }
        field(R.id.etLootGold, record.gold)
        field(R.id.etLootElixir, record.elixir)
        field(R.id.etLootDark, record.darkElixir)
        field(R.id.etLootBonusGold, record.bonusGold)
        field(R.id.etLootBonusElixir, record.bonusElixir)
        field(R.id.etLootBonusDark, record.bonusDarkElixir)

        val dialog = Dialog(this).apply {
            setContentView(view)
            window?.setType(overlayType())
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(dp(320), WindowManager.LayoutParams.WRAP_CONTENT)
            window?.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setCanceledOnTouchOutside(false)
        }
        lootDialog = dialog
        dialog.setOnDismissListener {
            if (lootDialog === dialog) lootDialog = null
        }

        view.findViewById<Button>(R.id.btnLootLater).setOnClickListener {
            dialog.dismiss()
            toast("Saved as unconfirmed in Attack history")
        }
        view.findViewById<Button>(R.id.btnLootConfirm).setOnClickListener {
            fun parse(id: Int): Long? =
                LootInput.parse(view.findViewById<EditText>(id).text.toString())

            val gold = parse(R.id.etLootGold)
            val elixir = parse(R.id.etLootElixir)
            val dark = parse(R.id.etLootDark)
            val bonusGold = parse(R.id.etLootBonusGold)
            val bonusElixir = parse(R.id.etLootBonusElixir)
            val bonusDark = parse(R.id.etLootBonusDark)
            val anyLoot = listOf(
                gold,
                elixir,
                dark,
                bonusGold,
                bonusElixir,
                bonusDark
            ).any { it != null }

            val updated = record.copy(
                gold = gold,
                elixir = elixir,
                darkElixir = dark,
                bonusGold = bonusGold,
                bonusElixir = bonusElixir,
                bonusDarkElixir = bonusDark,
                lootSource = if (anyLoot) {
                    AttackRecord.LOOT_MANUAL
                } else {
                    AttackRecord.LOOT_NONE
                },
                verified = anyLoot,
                confirmed = true
            )
            scope.launch {
                if (attackRepository.update(updated) != null) {
                    BotState.setStatus("Attack confirmed")
                    toast("Attack confirmed")
                } else {
                    toast("Could not save the attack")
                }
            }
            dialog.dismiss()
        }

        try {
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Loot dialog failed", e)
            if (lootDialog === dialog) lootDialog = null
            toast("Could not open the loot prompt")
        }
    }

    private fun dismissLootDialog() {
        val dialog = lootDialog
        lootDialog = null
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
            // Already dismissed.
        }
    }

    private fun accessibilityHint(): String =
        if (PermissionHelper.isAccessibilityEnabled(this)) {
            "Accessibility is enabled but still connecting — try again in a second"
        } else {
            "Enable the AutoClash accessibility service first"
        }

    private fun nextAttackId(): Long {
        val highest = attackRepository.records.value.maxOfOrNull { it.id } ?: 0L
        return maxOf(System.currentTimeMillis(), highest + 1L)
    }

    private fun renderStats() {
        val view = controllerView ?: return
        val records = attackRepository.records.value
        val summary = AttackStats.summarize(AttackStats.confirmedOnly(records))
        val state = BotState.state.value
        view.findViewById<TextView>(R.id.tvStatsState).text = when {
            state.isPlayback -> BotState.activeMacroName.value?.let { "Playing: " + it } ?: "Playing"
            state.isRecording -> "Recording"
            else -> "Idle"
        }
        view.findViewById<TextView>(R.id.tvStatsDetail).text =
            if (state.isPlayback) BotState.status.value else "No attack in progress"
        view.findViewById<TextView>(R.id.tvStatsTotals).text = lootTotalsText(summary)
        view.findViewById<TextView>(R.id.tvStatsTime).text =
            "Measured " + formatDuration(summary.totalDurationMs) +
                " · avg " + formatDuration(summary.averageDurationMs) +
                "\nRec: " + capturedGestures + " captured · " + rejectedGestures + " rejected"
        view.findViewById<TextView>(R.id.tvStatsRecent).text = if (records.isEmpty()) {
            "No attacks yet. Play a macro to start history."
        } else {
            records.take(4).joinToString("\n") { r ->
                formatClockOfDay(r.startedAt) + " · " + formatDuration(r.durationMs) + " · " + lootLabel(r)
            }
        }
    }

    private fun lootTotalsText(summary: AttackSummary): CharSequence {
        val rows = listOf(
            Triple(R.drawable.ic_gold, "Gold", lootText(summary.gold, summary.lootedCount)),
            Triple(R.drawable.ic_elixir, "Elixir", lootText(summary.elixir, summary.lootedCount)),
            Triple(R.drawable.ic_dark, "Dark", lootText(summary.darkElixir, summary.lootedCount))
        )
        val builder = SpannableStringBuilder("Attacks: " + summary.count)
        rows.forEach { row ->
            builder.append("\n")
            val start = builder.length
            builder.append(" ")
            val drawable = ContextCompat.getDrawable(this, row.first)?.mutate()
            if (drawable != null) {
                drawable.setBounds(0, 0, dp(11), dp(11))
                builder.setSpan(
                    ImageSpan(drawable),
                    start,
                    start + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            builder.append(" " + row.second + ": " + row.third)
        }
        return builder
    }

    private fun lootText(value: Long, lootedCount: Int): String =
        if (lootedCount == 0) "not captured" else value.toString()

    private fun lootLabel(record: AttackRecord): String {
        if (!record.hasLoot) return "loot not captured"
        return "G " + (record.gold ?: 0) + " · E " + (record.elixir ?: 0) +
            " · DE " + (record.darkElixir ?: 0)
    }

    private fun formatClockOfDay(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(timestamp))

    private fun changeSpeed(direction: Int) {
        speed = MacroTimeline.stepSpeed(speed, direction)
        persistPlaybackSettings()
        refreshUi()
    }

    private fun cycleRepeat() {
        repeatMode = MacroTimeline.nextRepeat(repeatMode)
        persistPlaybackSettings()
        refreshUi()
    }

    private fun persistPlaybackSettings() {
        val id = selectedId
        if (id <= 0) return
        scope.launch { repository.updatePlayback(id, speed, repeatMode) }
    }

    // ----------------------------------------------------------- macro library

    private fun refreshMacros() {
        scope.launch { repository.refresh() }
    }

    private fun selectMacro(macro: Macro) {
        selectedId = macro.id
        speed = MacroTimeline.clampSpeed(macro.speedMultiplier)
        repeatMode = macro.repeatMode
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
        val rowContext = view.context

        view.findViewById<TextView>(R.id.tvLibraryLabel)?.text =
            if (macros.isEmpty()) "Macros" else "Macros · ${macros.size}"

        if (macros.isEmpty()) {
            container.addView(TextView(rowContext).apply {
                text = "No macros yet. Record one to get started."
                setTextColor(0xFF94A3B8.toInt())
                textSize = 10f
                setPadding(dp(rowContext, 6), dp(rowContext, 8), dp(rowContext, 6), dp(rowContext, 8))
            })
            return
        }

        for (macro in macros) {
            val row = LinearLayout(rowContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(rowContext, 8),
                    dp(rowContext, 6),
                    dp(rowContext, 8),
                    dp(rowContext, 6)
                )
                setBackgroundColor(if (macro.id == selectedId) 0x2E3B82F6 else Color.TRANSPARENT)
            }
            row.addView(TextView(rowContext).apply {
                text = macro.name
                setTextColor(0xFFE2E8F0.toInt())
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(TextView(rowContext).apply {
                text = "${macro.gestures.size} gestures · ${formatDuration(macro.durationMs)} · " +
                    MacroTimeline.formatSpeed(macro.speedMultiplier) + " · " +
                    MacroTimeline.repeatLabel(macro.repeatMode)
                setTextColor(0xFF94A3B8.toInt())
                textSize = 9f
            })
            row.setOnClickListener { selectMacro(macro) }
            container.addView(row)
        }
    }

    private fun saveRecording() {
        if (recorded.isEmpty()) {
            toast("Nothing to save")
            return
        }
        val defaultName = "Macro " + SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date())

        fun persist(name: String) {
            val gestures = recorded.toList()
            val macro = Macro(
                id = nextMacroId(),
                name = name,
                createdAt = System.currentTimeMillis(),
                durationMs = MacroTimeline.totalDuration(gestures),
                deviceWidth = screenWidth,
                deviceHeight = screenHeight,
                orientation = currentOrientation(),
                gestures = gestures,
                repeatMode = repeatMode,
                speedMultiplier = speed
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

    private fun renameSelected() {
        val macro = macros.firstOrNull { it.id == selectedId } ?: run {
            toast("Select a macro first")
            return
        }
        showInputDialog("Rename macro", macro.name) { name ->
            scope.launch {
                if (repository.rename(macro.id, name) == null) {
                    toast("Rename failed")
                } else {
                    BotState.setStatus("Renamed to \"$name\"")
                    toast("Renamed")
                }
            }
        }
    }

    private fun deleteSelected() {
        val macro = macros.firstOrNull { it.id == selectedId } ?: run {
            toast("Select a macro first")
            return
        }
        showConfirmDialog(
            title = "Delete macro?",
            message = "\"${macro.name}\" will be removed.",
            positive = "Delete"
        ) {
            scope.launch {
                if (repository.delete(macro.id)) {
                    BotState.setStatus("Deleted \"${macro.name}\"")
                } else {
                    toast("Delete failed")
                }
            }
        }
    }

    private fun nextMacroId(): Long {
        val now = System.currentTimeMillis()
        val highest = macros.maxOfOrNull { it.id } ?: 0L
        return maxOf(now, highest + 1)
    }

    // -------------------------------------------------------------------- ui

    private fun refreshUi() {
        val view = controllerView ?: return
        val state = BotState.state.value
        val count = BotState.recordedGestureCount.value

        val showRecording = state == ControllerState.RECORDING || state == ControllerState.RECORDING_PAUSED
        val showReview = state == ControllerState.REVIEW
        val showPlaying = state == ControllerState.PLAYING || state == ControllerState.PLAYBACK_PAUSED
        val showReady = !showRecording && !showReview && !showPlaying

        val statsVisible = page == PAGE_STATS
        view.findViewById<View>(R.id.statsPanel).visibility = if (statsVisible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.readyPanel).visibility = if (!statsVisible && showReady) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.recordingPanel).visibility = if (!statsVisible && showRecording) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.reviewPanel).visibility = if (!statsVisible && showReview) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.playingPanel).visibility = if (!statsVisible && showPlaying) View.VISIBLE else View.GONE
        val tabActive = 0xFF3B82F6.toInt()
        val tabIdle = 0xFF1E293B.toInt()
        view.findViewById<Button>(R.id.btnPageController).backgroundTintList =
            ColorStateList.valueOf(if (statsVisible) tabIdle else tabActive)
        view.findViewById<Button>(R.id.btnPageStats).backgroundTintList =
            ColorStateList.valueOf(if (statsVisible) tabActive else tabIdle)

        view.findViewById<TextView>(R.id.tvSpeed).text = MacroTimeline.formatSpeed(speed)
        view.findViewById<Button>(R.id.btnRepeat).text = MacroTimeline.repeatLabel(repeatMode)

        view.findViewById<Button>(R.id.btnPlay).isEnabled = selectedId > 0
        view.findViewById<Button>(R.id.btnRecord).isEnabled = !startingPassive
        view.findViewById<ImageButton>(R.id.btnRename).isEnabled = selectedId > 0
        view.findViewById<ImageButton>(R.id.btnDelete).isEnabled = selectedId > 0

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
        view.findViewById<TextView>(R.id.tvPlayingSpeed).text =
            "Speed ${MacroTimeline.formatSpeed(speed)}"
        view.findViewById<TextView>(R.id.tvPlayingRepeat).text =
            MacroTimeline.repeatLabel(repeatMode)

        val bubbleStatus = view.findViewById<TextView>(R.id.bubbleStatus)
        bubbleStatus.text = when (state) {
            ControllerState.RECORDING -> "REC"
            ControllerState.RECORDING_PAUSED -> "PAUSED"
            ControllerState.PLAYING -> "PLAY"
            ControllerState.PLAYBACK_PAUSED -> "II"
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
        stopPlayback()
        stopRecording(silent = true)
        stopPassiveRecorder()
        removeIndicator()
        dismissLootDialog()
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

    private data class AttackSession(
        val startedAt: Long,
        val macroId: Long,
        val macroName: String,
        val speed: Float,
        val repeat: String
    )

    companion object {
        private const val PAGE_CONTROLLER = 0
        private const val PAGE_STATS = 1
        private const val TAG = "AutoClashController"
        private const val CHANNEL_ID = "autoclash_controller"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_THROTTLE_MS = 900L
        private const val STATUS_THROTTLE_MS = 500L
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
