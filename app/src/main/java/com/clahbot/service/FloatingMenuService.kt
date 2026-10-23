package com.clahbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
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
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clahbot.R
import com.clahbot.data.BotState
import com.clahbot.data.ControllerState
import com.clahbot.data.MacroRepository
import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.execution.MacroPlayer
import com.clahbot.execution.MacroTimeline
import com.clahbot.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Owns the floating controller, the touch recorder and macro playback.
 *
 * Window layering is deliberate: the touch capture surface is a full screen
 * window, and the controller is re-added after it so the controller is always
 * the topmost window. A gesture on a controller button therefore never reaches
 * the capture surface and is never recorded as gameplay input.
 */
class FloatingMenuService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: MacroRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var controllerView: View? = null
    private var controllerParams: WindowManager.LayoutParams? = null
    private var captureView: View? = null
    private var captureParams: WindowManager.LayoutParams? = null

    private var player: MacroPlayer? = null

    private var macros: List<Macro> = emptyList()
    private var selectedId: Long = -1L
    private var speed: Float = 1f
    private var looping: Boolean = true

    private var recording: Boolean = false
    private var injecting: Boolean = false
    private var injectToken: Int = 0
    private var recorded = mutableListOf<RecordedGesture>()
    private var recordStartMs: Long = 0L
    private var gestureSeq: Int = 0

    private var downTime: Long = 0L
    private var pathPoints = mutableListOf<GesturePoint>()

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var notificationStarted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repository = MacroRepository.get(this)
        updateScreenSize()
        startForegroundCompat(getString(R.string.notification_idle))
        scope.launch { BotState.state.collect { refreshUi() } }
        scope.launch { BotState.status.collect { onStatusChanged(it) } }
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

            ACTION_CLOSE -> closeEverything()
            else -> ensureController()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenSize()
        if (recording) {
            stopRecording()
            toast("Recording stopped: screen changed")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPlayback()
        stopRecording(silent = true)
        removeCapture()
        removeController()
        scope.cancel()
        super.onDestroy()
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
            expandController()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_controller, null)
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
            y = dp(88)
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
        refreshUi()
        refreshMacros()
    }

    private fun removeController() {
        val view = controllerView ?: return
        controllerView = null
        controllerParams = null
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

        view.findViewById<Button>(R.id.btnMinimize).setOnClickListener { collapseController() }
        view.findViewById<Button>(R.id.btnCloseController).setOnClickListener { closeEverything() }
        view.findViewById<Button>(R.id.btnRecord).setOnClickListener { startRecording() }
        view.findViewById<Button>(R.id.btnStopRecording).setOnClickListener { stopRecording() }
        view.findViewById<Button>(R.id.btnPlay).setOnClickListener { playSelected() }
        view.findViewById<Button>(R.id.btnPause).setOnClickListener { togglePause() }
        view.findViewById<Button>(R.id.btnStopPlayback).setOnClickListener { stopPlayback() }
        view.findViewById<Button>(R.id.btnLoop).setOnClickListener { toggleLoop() }
        view.findViewById<Button>(R.id.btnSpeedDown).setOnClickListener { changeSpeed(-1) }
        view.findViewById<Button>(R.id.btnSpeedUp).setOnClickListener { changeSpeed(1) }
        view.findViewById<Button>(R.id.btnSave).setOnClickListener { saveRecording() }
        view.findViewById<Button>(R.id.btnDiscard).setOnClickListener { discardRecording() }
        view.findViewById<Button>(R.id.btnRename).setOnClickListener { renameSelected() }
        view.findViewById<Button>(R.id.btnDelete).setOnClickListener { deleteSelected() }
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
        view.findViewById<View>(R.id.panel).visibility = View.GONE
        view.findViewById<View>(R.id.bubble).visibility = View.VISIBLE
        val width = controllerView?.width ?: 0
        if (width > 0) params.x = clampX(params.x)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Ignore.
        }
    }

    private fun expandController() {
        val view = controllerView ?: return
        val params = controllerParams ?: return
        view.findViewById<View>(R.id.bubble).visibility = View.GONE
        view.findViewById<View>(R.id.panel).visibility = View.VISIBLE
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Ignore.
        }
    }

    // -------------------------------------------------------------- recording

    private fun startRecording() {
        if (recording) return
        if (ClahAccessibilityService.instance == null) {
            toast("Enable the ClahBOT accessibility service first")
            return
        }
        if (BotState.isBusy()) stopPlayback()
        if (controllerView == null) ensureController()
        if (controllerView == null) return

        recorded.clear()
        pathPoints.clear()
        gestureSeq = 0
        recordStartMs = SystemClock.uptimeMillis()
        injecting = false
        recording = true
        BotState.clearRecording()
        BotState.setState(ControllerState.RECORDING)
        BotState.setStatus("Recording — play normally")
        attachCaptureSurface()
        refreshUi()
    }

    /**
     * Adds the full screen capture window and then re-adds the controller so
     * the controller stays above it. Touches on controller buttons never enter
     * the capture surface.
     */
    private fun attachCaptureSurface() {
        if (captureView == null) {
            val view = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
            view.setOnTouchListener { _, event -> onCaptureTouch(event) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                windowManager.addView(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add capture window", e)
                recording = false
                BotState.setState(ControllerState.IDLE)
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
        val now = SystemClock.uptimeMillis()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (injecting) return true
                downTime = now
                pathPoints = mutableListOf(GesturePoint(event.x, event.y, 0L))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (injecting) return true
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
                if (injecting) return true
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
        val view = captureView ?: return
        val width = view.width.takeIf { it > 0 } ?: screenWidth
        val height = view.height.takeIf { it > 0 } ?: screenHeight
        if (width <= 0 || height <= 0) {
            pathPoints.clear()
            return
        }

        val rawPoints = pathPoints.toList()
        pathPoints = mutableListOf()
        if (rawPoints.isEmpty()) return

        val sampled = MacroTimeline.decimate(rawPoints)
        val deviation = MacroTimeline.maxDeviationPx(sampled)
        val type = MacroTimeline.classify(deviation, durationMs)
        val normalized = MacroTimeline.normalize(sampled, width, height)
        if (normalized.isEmpty()) return

        val gesture = RecordedGesture(
            id = ++gestureSeq,
            timestampMs = (downTime - recordStartMs).coerceAtLeast(0L),
            gestureType = type,
            durationMs = durationMs.coerceIn(1L, MacroTimeline.MAX_STROKE_MS),
            normalizedStartX = normalized.first().x,
            normalizedStartY = normalized.first().y,
            normalizedEndX = normalized.last().x,
            normalizedEndY = normalized.last().y,
            points = normalized
        )
        recorded.add(gesture)
        BotState.setRecordedCount(recorded.size)
        BotState.setStatus("Recording — ${recorded.size} gesture(s)")
        refreshUi()

        injectRecordedGesture(sampled, type, durationMs)
    }

    /**
     * Replays the gesture into the game while the capture surface is not
     * touchable, otherwise the injected gesture would land on our own overlay.
     */
    private fun injectRecordedGesture(
        pointsPx: List<GesturePoint>,
        type: String,
        durationMs: Long
    ) {
        val service = ClahAccessibilityService.instance ?: return
        if (pointsPx.isEmpty()) return

        val path = Path()
        val first = pointsPx.first()
        path.moveTo(first.x, first.y)
        if (type == "swipe") {
            for (i in 1 until pointsPx.size) path.lineTo(pointsPx[i].x, pointsPx[i].y)
        }

        val duration = when (type) {
            "swipe" -> durationMs.coerceIn(MacroTimeline.MIN_DRAG_STROKE_MS, MacroTimeline.MAX_STROKE_MS)
            "long_press" -> durationMs.coerceIn(MacroTimeline.MIN_LONG_PRESS_STROKE_MS, MacroTimeline.MAX_STROKE_MS)
            else -> MacroTimeline.TAP_MS
        }

        val token = ++injectToken
        injecting = true
        setCaptureTouchable(false)

        handler.postDelayed({
            if (!recording || token != injectToken) {
                restoreCaptureTouch(token)
                return@postDelayed
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            val description = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    restoreCaptureTouch(token)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    restoreCaptureTouch(token)
                }
            }
            val accepted = service.dispatchGesture(description, callback, null)
            if (!accepted) restoreCaptureTouch(token)
        }, INJECT_DELAY_MS)

        handler.postDelayed({ restoreCaptureTouch(token) }, INJECT_DELAY_MS + duration + 700L)
    }

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

    private fun restoreCaptureTouch(token: Int) {
        if (token != injectToken) return
        if (!injecting) return
        injecting = false
        if (recording) setCaptureTouchable(true)
    }

    private fun stopRecording(silent: Boolean = false) {
        if (!recording) return
        recording = false
        injecting = false
        injectToken++
        pathPoints.clear()
        removeCapture()
        BotState.setRecordedCount(recorded.size)
        if (recorded.isEmpty()) {
            BotState.setState(ControllerState.IDLE)
            BotState.setStatus("Nothing recorded")
            if (!silent) toast("No gestures recorded")
        } else {
            BotState.setState(ControllerState.READY)
            BotState.setStatus("${recorded.size} gesture(s) — save or discard")
        }
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
            toast("Stop recording first")
            return
        }
        if (ClahAccessibilityService.instance == null) {
            toast("Enable the ClahBOT accessibility service")
            return
        }
        updateScreenSize()
        if (screenWidth <= 0 || screenHeight <= 0) {
            toast("Display size unavailable")
            return
        }

        val adjusted = macro.copy(
            speedMultiplier = speed,
            repeatMode = if (looping) Macro.REPEAT_INFINITE else Macro.REPEAT_ONCE
        )
        if (adjusted.orientation != currentOrientation()) {
            BotState.setStatus("Warning: macro was recorded in ${adjusted.orientation}")
        }

        player?.stop()
        val newPlayer = MacroPlayer(screenWidth, screenHeight)
        player = newPlayer
        newPlayer.play(adjusted, scope)
        updateNotification(getString(R.string.notification_playing, macro.name))
        refreshUi()
    }

    private fun togglePause() {
        val active = player ?: return
        if (active.isPaused()) active.resume() else active.pause()
        refreshUi()
    }

    private fun stopPlayback() {
        player?.stop()
        player = null
        updateNotification(getString(R.string.notification_idle))
        refreshUi()
    }

    private fun toggleLoop() {
        looping = !looping
        persistPlaybackSettings()
        refreshUi()
    }

    private fun changeSpeed(direction: Int) {
        speed = MacroTimeline.stepSpeed(speed, direction)
        persistPlaybackSettings()
        refreshUi()
    }

    private fun persistPlaybackSettings() {
        val id = selectedId
        if (id <= 0) return
        val repeat = if (looping) Macro.REPEAT_INFINITE else Macro.REPEAT_ONCE
        scope.launch {
            val updated = repository.updatePlayback(id, speed, repeat)
            if (updated != null) {
                macros = macros.map { if (it.id == updated.id) updated else it }
                renderMacroList()
            }
        }
    }

    // ----------------------------------------------------------- macro storage

    private fun refreshMacros() {
        scope.launch {
            macros = repository.load()
            if (selectedId <= 0) {
                BotState.setSelectedMacroId(selectedId)
            }
            renderMacroList()
            refreshUi()
        }
    }

    private fun selectMacro(macro: Macro) {
        selectedId = macro.id
        speed = MacroTimeline.clampSpeed(macro.speedMultiplier)
        looping = macro.loops
        BotState.setSelectedMacroId(macro.id)
        renderMacroList()
        refreshUi()
    }

    private fun renderMacroList() {
        val view = controllerView ?: return
        val container = view.findViewById<LinearLayout>(R.id.macroList) ?: return
        container.removeAllViews()

        if (macros.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No macros yet"
                setTextColor(0xFF888888.toInt())
                textSize = 10f
                setPadding(dp(8), dp(6), dp(8), dp(6))
            })
            return
        }

        for (macro in macros) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setBackgroundColor(if (macro.id == selectedId) 0x334CAF50 else Color.TRANSPARENT)
            }
            row.addView(TextView(this).apply {
                text = macro.name
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
            })
            row.addView(TextView(this).apply {
                text = "${macro.gestures.size} gestures · ${formatDuration(macro.durationMs)} · " +
                    MacroTimeline.formatSpeed(macro.speedMultiplier)
                setTextColor(0xFFAAAAAA.toInt())
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
        showInputDialog("Save macro", defaultName) { name ->
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
                repeatMode = if (looping) Macro.REPEAT_INFINITE else Macro.REPEAT_ONCE,
                speedMultiplier = speed
            )
            scope.launch {
                val saved = repository.save(macro)
                if (saved == null) {
                    toast("Could not save macro")
                    return@launch
                }
                recorded.clear()
                BotState.clearRecording()
                selectedId = saved.id
                BotState.setSelectedMacroId(saved.id)
                BotState.setState(ControllerState.IDLE)
                BotState.setStatus("Saved \"${saved.name}\"")
                refreshMacros()
                toast("Macro saved")
            }
        }
    }

    private fun discardRecording() {
        if (recorded.isEmpty()) {
            BotState.setState(ControllerState.IDLE)
            refreshUi()
            return
        }
        showConfirmDialog(
            title = "Discard recording?",
            message = "${recorded.size} gesture(s) will be lost.",
            positive = "Discard"
        ) {
            recorded.clear()
            BotState.clearRecording()
            BotState.setState(ControllerState.IDLE)
            BotState.setStatus("Recording discarded")
            refreshUi()
        }
    }

    private fun renameSelected() {
        val macro = macros.firstOrNull { it.id == selectedId } ?: run {
            toast("Select a macro first")
            return
        }
        showInputDialog("Rename macro", macro.name) { name ->
            scope.launch {
                val updated = repository.rename(macro.id, name)
                if (updated == null) {
                    toast("Rename failed")
                } else {
                    refreshMacros()
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
                val removed = repository.delete(macro.id)
                if (removed) {
                    selectedId = -1L
                    BotState.setSelectedMacroId(-1L)
                    BotState.setStatus("Deleted \"${macro.name}\"")
                    refreshMacros()
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

        view.findViewById<TextView>(R.id.tvSpeed).text = MacroTimeline.formatSpeed(speed)
        view.findViewById<Button>(R.id.btnLoop).text = if (looping) "Loop: On" else "Loop: Once"

        val isRecording = recording || state == ControllerState.RECORDING
        val isReady = state == ControllerState.READY && count > 0
        val isPlaying = state == ControllerState.PLAYING
        val isPaused = state == ControllerState.PAUSED
        val busy = isPlaying || isPaused

        view.findViewById<Button>(R.id.btnRecord).visibility =
            if (isRecording) View.GONE else View.VISIBLE
        view.findViewById<Button>(R.id.btnStopRecording).visibility =
            if (isRecording) View.VISIBLE else View.GONE

        view.findViewById<Button>(R.id.btnPlay).isEnabled = !isRecording && !busy && selectedId > 0
        view.findViewById<Button>(R.id.btnPause).isEnabled = busy
        view.findViewById<Button>(R.id.btnPause).text = if (isPaused) "Resume" else "Pause"
        view.findViewById<Button>(R.id.btnStopPlayback).isEnabled = busy
        view.findViewById<Button>(R.id.btnRecord).isEnabled = !busy

        view.findViewById<Button>(R.id.btnLoop).isEnabled = !isRecording
        view.findViewById<Button>(R.id.btnSpeedDown).isEnabled = !isRecording
        view.findViewById<Button>(R.id.btnSpeedUp).isEnabled = !isRecording

        view.findViewById<LinearLayout>(R.id.saveRow).visibility =
            if (isReady) View.VISIBLE else View.GONE

        val countView = view.findViewById<TextView>(R.id.tvRecordingCount)
        countView.visibility = if (isRecording) View.VISIBLE else View.GONE
        countView.text = "$count gesture(s) captured"

        view.findViewById<Button>(R.id.btnRename).isEnabled = selectedId > 0 && !isRecording
        view.findViewById<Button>(R.id.btnDelete).isEnabled = selectedId > 0 && !isRecording

        val bubbleStatus = view.findViewById<TextView>(R.id.bubbleStatus)
        bubbleStatus.text = when {
            isRecording -> "REC"
            isPlaying -> "PLAY"
            isPaused -> "PAUSED"
            else -> ""
        }
    }

    private fun onStatusChanged(status: String) {
        controllerView?.findViewById<TextView>(R.id.tvControllerStatus)?.text = status
        if (BotState.state.value == ControllerState.PLAYING ||
            BotState.state.value == ControllerState.PAUSED
        ) {
            updateNotification(status)
        }
    }

    // ------------------------------------------------------------- dialogs

    private fun showInputDialog(title: String, initial: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setType(overlayType())
        dialog.window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    input.error = "Name cannot be empty"
                    return@setOnClickListener
                }
                dialog.dismiss()
                onConfirm(value)
            }
        }
        try {
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Input dialog failed", e)
            toast("Could not open dialog")
        }
    }

    private fun showConfirmDialog(title: String, message: String, positive: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setType(overlayType())
        try {
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Confirm dialog failed", e)
            toast("Could not open dialog")
        }
    }

    // -------------------------------------------------------- notification

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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        removeCapture()
        removeController()
        BotState.setState(ControllerState.IDLE)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            // Toast can fail if the app is going away.
        }
    }

    companion object {
        private const val TAG = "ClahBotController"
        private const val CHANNEL_ID = "clahbot_controller"
        private const val NOTIFICATION_ID = 1001
        private const val INJECT_DELAY_MS = 24L
        private const val EXTRA_MACRO_ID = "macro_id"

        const val ACTION_PLAY = "com.clahbot.action.PLAY"
        const val ACTION_CLOSE = "com.clahbot.action.CLOSE"

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

        fun close(context: Context) {
            context.startService(
                Intent(context, FloatingMenuService::class.java).setAction(ACTION_CLOSE)
            )
        }
    }
}
