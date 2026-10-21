package com.clahbot.service

import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.clahbot.R
import com.clahbot.data.ConfigRepository
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.execution.MacroPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FloatingMenuService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var builderOverlay: View? = null
    private var macroPlayer: MacroPlayer? = null
    private var playbackScope: CoroutineScope? = null
    private var selectedMacroId: Long = -1
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isBuilding = false
    private var isPlaying = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var windowX = 0
    private var windowY = 0

    private val recordedGestures = mutableListOf<RecordedGesture>()
    private var buildStartTime = 0L
    private val markerPositions = mutableListOf<Pair<Float, Float>>()
    private var pendingSwipeStart: Pair<Float, Float>? = null
    private var builderMode = "tap"

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        getScreenSize()
    }

    private fun getScreenSize() {
        try {
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val bounds = wm.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
            }
        } catch (_: Exception) {
            screenWidth = 1080
            screenHeight = 2400
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(this, "Overlay permission required", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (floatingView == null) showFloatingMenu()
        return START_NOT_STICKY
    }

    private fun showFloatingMenu() {
        try {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.overlay_floating_menu, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenWidth * 0.05).toInt().coerceAtLeast(0)
                y = (screenHeight * 0.25).toInt().coerceAtLeast(0)
            }

            windowManager.addView(view, params)
            floatingView = view
            windowX = params.x
            windowY = params.y
            setupFloatingTouchHandlers(view, params)
            loadMacroList()
        } catch (e: Exception) {
            android.util.Log.e("FloatingMenu", "showFloatingMenu failed", e)
            android.widget.Toast.makeText(this, "Failed to show floating menu: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun setupFloatingTouchHandlers(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    windowX = params.x
                    windowY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val dx = (event.rawX - dragStartX).toInt()
                        val dy = (event.rawY - dragStartY).toInt()
                        params.x = (windowX + dx).coerceAtLeast(0)
                        params.y = (windowY + dy).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        view.findViewById<Button>(R.id.btnFloatingClose).setOnClickListener { stopService() }
        view.findViewById<Button>(R.id.btnRecord).setOnClickListener { handleNewMacro() }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener { handleFinishMacro() }
        view.findViewById<Button>(R.id.btnSave).setOnClickListener { handleSaveTap() }
        view.findViewById<Button>(R.id.btnRecordAgain).setOnClickListener { handleClearMacro() }
        view.findViewById<Button>(R.id.btnPlayLoop).setOnClickListener { handlePlayLoopTap() }
        view.findViewById<Button>(R.id.btnDelete).setOnClickListener { handleDeleteTap() }
    }

    private fun handleNewMacro() {
        if (com.clahbot.service.ClahAccessibilityService.instance == null) {
            showToast("Accessibility service not enabled")
            return
        }
        if (isPlaying) return
        isBuilding = true
        recordedGestures.clear()
        markerPositions.clear()
        pendingSwipeStart = null
        builderMode = "tap"
        buildStartTime = System.currentTimeMillis()
        showBuilderOverlay()
        updateBuilderUI()
        showToast("Tap on screen to add gestures")
    }

    private fun handleFinishMacro() {
        if (!isBuilding) return
        isBuilding = false
        builderMode = "tap"
        pendingSwipeStart = null
        hideBuilderOverlay()
        updateIdleUI()
        if (recordedGestures.isEmpty()) {
            showToast("No gestures added")
        } else {
            showToast("Macro has ${recordedGestures.size} gestures")
            showSaveNameDialog()
        }
    }

    private fun handleSaveTap() {
        if (recordedGestures.isEmpty()) {
            showToast("No gestures recorded")
            return
        }
        showSaveNameDialog()
    }

    private fun handleClearMacro() {
        recordedGestures.clear()
        markerPositions.clear()
        pendingSwipeStart = null
        builderMode = "tap"
        showToast("Cleared")
    }

    private fun handlePlayLoopTap() {
        if (isPlaying) {
            macroPlayer?.stop()
            macroPlayer = null
            isPlaying = false
            updatePlayLoopButton(false)
            return
        }
        if (selectedMacroId < 0) {
            showToast("Select a macro from the list first")
            return
        }
        if (isBuilding) return

        serviceScope.launch {
            val macros = ConfigRepository.from(this@FloatingMenuService).loadMacros()
            val macro = macros.find { it.id == selectedMacroId } ?: return@launch
            val playbackMacro = macro.copy(repeatMode = "infinite")
            val pScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            playbackScope = pScope
            macroPlayer = MacroPlayer(screenWidth, screenHeight)
            isPlaying = true
            updatePlayLoopButton(true)
            macroPlayer?.play(playbackMacro, pScope)
        }
    }

    private fun handleDeleteTap() {
        if (selectedMacroId < 0) {
            showToast("Select a macro to delete")
            return
        }
        showDeleteDialog()
    }

    private fun showBuilderOverlay() {
        try {
            val overlay = object : View(this) {
                private val markerPaint = Paint().apply {
                    color = 0xFF4CAF50.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                private val fillPaint = Paint().apply {
                    color = 0x6600FF00.toInt()
                    style = Paint.Style.FILL
                }
                private val swipePaint = Paint().apply {
                    color = 0xFFFF9800.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                private val centerPaint = Paint().apply {
                    color = 0xFFFF4444.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                private val gridPaint = Paint().apply {
                    color = 0x22FFFFFF.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                }
                private val coordPaint = Paint().apply {
                    color = 0xAAFFFFFF.toInt()
                    textSize = 28f
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    for (i in 0 until width step 80) {
                        canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), gridPaint)
                    }
                    for (i in 0 until height step 80) {
                        canvas.drawLine(0f, i.toFloat(), width.toFloat(), i.toFloat(), gridPaint)
                    }
                    for ((px, py) in markerPositions) {
                        canvas.drawCircle(px, py, 24f, markerPaint)
                        canvas.drawCircle(px, py, 20f, fillPaint)
                        var label = "(${(px / width * 100).toInt()}, ${(py / height * 100).toInt()})"
                        canvas.drawText(label, px + 28f, py + 8f, coordPaint)
                    }
                    if (pendingSwipeStart != null && builderMode == "swipe") {
                        val (sx, sy) = pendingSwipeStart!!
                        canvas.drawCircle(sx, sy, 20f, swipePaint)
                        canvas.drawText("SWIPE START", sx + 24f, sy + 8f, coordPaint)
                    }
                    if (isBuilding) {
                        val status = "BUILDING: ${recordedGestures.size} gestures | ${builderMode.uppercase()} mode"
                        canvas.drawText(status, 20f, height - 20f, coordPaint)
                    }
                }
            }
            overlay.setBackgroundColor(0x44000000)
            overlay.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val normX = event.x / screenWidth
                    val normY = event.y / screenHeight
                    val now = System.currentTimeMillis() - buildStartTime

                    if (builderMode == "swipe" && pendingSwipeStart != null) {
                        val (sx, sy) = pendingSwipeStart!!
                        val snormX = sx / screenWidth
                        val snormY = sy / screenHeight
                        recordedGestures.add(RecordedGesture(
                            id = recordedGestures.size + 1,
                            timestampMs = (now - 200).coerceAtLeast(0),
                            gestureType = "swipe",
                            durationMs = 200,
                            normalizedStartX = snormX,
                            normalizedStartY = snormY,
                            normalizedEndX = normX,
                            normalizedEndY = normY,
                            pressure = 0f
                        ))
                        val ex = event.x
                        val ey = event.y
                        markerPositions.add(Pair(ex, ey))
                        pendingSwipeStart = null
                        builderMode = "tap"
                        showToast("Swipe added")
                    } else {
                        recordedGestures.add(RecordedGesture(
                            id = recordedGestures.size + 1,
                            timestampMs = now,
                            gestureType = "tap",
                            durationMs = 50,
                            normalizedStartX = normX,
                            normalizedStartY = normY,
                            normalizedEndX = normX,
                            normalizedEndY = normY,
                            pressure = 0f
                        ))
                        markerPositions.add(Pair(event.x, event.y))
                        showToast("Tap added at (${"%.0f".format(event.x)}, ${"%.0f".format(event.y)})")
                    }
                    overlay.invalidate()
                    updateBuilderUI()
                }
                true
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(overlay, params)
            builderOverlay = overlay
        } catch (e: Exception) {
            android.util.Log.e("FloatingMenu", "showBuilderOverlay failed", e)
            showToast("Failed to show builder")
            isBuilding = false
        }
    }

    private fun hideBuilderOverlay() {
        try { builderOverlay?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        builderOverlay = null
    }

    private fun updateBuilderUI() {
        val view = floatingView ?: return
        view.findViewById<Button>(R.id.btnRecord).visibility = View.GONE
        view.findViewById<Button>(R.id.btnStop).visibility = View.VISIBLE
        view.findViewById<Button>(R.id.btnSave).visibility = View.GONE
        view.findViewById<Button>(R.id.btnRecordAgain).visibility = View.GONE
        view.findViewById<Button>(R.id.btnPlayLoop).isEnabled = false
        view.findViewById<Button>(R.id.btnDelete).isEnabled = false
        view.findViewById<TextView>(R.id.tvRecStatus).text = "${recordedGestures.size} gestures"
    }

    private fun updateIdleUI() {
        val view = floatingView ?: return
        view.findViewById<Button>(R.id.btnRecord).visibility = View.VISIBLE
        view.findViewById<Button>(R.id.btnStop).visibility = View.GONE
        view.findViewById<Button>(R.id.btnSave).visibility = View.GONE
        view.findViewById<Button>(R.id.btnRecordAgain).visibility = View.GONE
        view.findViewById<Button>(R.id.btnPlayLoop).isEnabled = true
        view.findViewById<Button>(R.id.btnDelete).isEnabled = true
        view.findViewById<TextView>(R.id.tvRecStatus).text = ""
    }

    private fun updatePlayLoopButton(playing: Boolean) {
        val view = floatingView ?: return
        view.findViewById<Button>(R.id.btnPlayLoop).text = if (playing) "⏹ Stop" else "▶ Loop"
        view.findViewById<Button>(R.id.btnPlayLoop).isEnabled = true
        if (!playing) {
            isPlaying = false
            selectedMacroId = -1
        }
    }

    private fun showSaveNameDialog() {
        val editText = EditText(this).apply {
            setText("Macro ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())}")
            setSelection(text.length)
        }
        AlertDialog.Builder(this).apply {
            setTitle("Save Macro")
            setView(editText)
            setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString().trim().ifBlank {
                    "Macro ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())}"
                }
                val totalDuration = if (recordedGestures.isNotEmpty())
                    recordedGestures.last().timestampMs + recordedGestures.last().durationMs else 0L
                val macro = Macro(
                    id = System.currentTimeMillis(),
                    name = name,
                    createdAt = System.currentTimeMillis(),
                    durationMs = totalDuration,
                    deviceWidth = screenWidth,
                    deviceHeight = screenHeight,
                    orientation = if (screenWidth > screenHeight) "landscape" else "portrait",
                    gestures = recordedGestures.toList()
                )
                serviceScope.launch {
                    ConfigRepository.from(this@FloatingMenuService).saveMacro(macro)
                    loadMacroList()
                }
                recordedGestures.clear()
                markerPositions.clear()
                showToast("Macro saved")
            }
            setNegativeButton("Cancel", null)
        }.show()
    }

    private fun showDeleteDialog() {
        serviceScope.launch {
            val macros = ConfigRepository.from(this@FloatingMenuService).loadMacros()
            val macro = macros.find { it.id == selectedMacroId } ?: return@launch
            AlertDialog.Builder(this@FloatingMenuService).apply {
                setTitle("Delete Macro")
                setMessage("Delete \"${macro.name}\"?")
                setPositiveButton("Delete") { _, _ ->
                    serviceScope.launch {
                        ConfigRepository.from(this@FloatingMenuService).deleteMacro(macro.id)
                        selectedMacroId = -1
                        loadMacroList()
                        showToast("Deleted")
                    }
                }
                setNegativeButton("Cancel", null)
            }.show()
        }
    }

    private fun loadMacroList() {
        serviceScope.launch {
            try {
                val macros = ConfigRepository.from(this@FloatingMenuService).loadMacros()
                    .sortedByDescending { it.createdAt }
                val view = floatingView ?: return@launch
                val container = view.findViewById<LinearLayout>(R.id.macroList)
                container.removeAllViews()
                if (macros.isEmpty()) {
                    val tv = TextView(this@FloatingMenuService).apply {
                        text = "No macros saved"
                        setTextColor(0xFF888888.toInt())
                        textSize = 11f
                        setPadding(16, 12, 16, 12)
                    }
                    container.addView(tv)
                } else {
                    for (macro in macros) {
                        val row = LinearLayout(this@FloatingMenuService).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(8, 6, 8, 6)
                            setBackgroundColor(if (macro.id == selectedMacroId) 0xFF1A3A2E.toInt() else 0x00000000)
                        }
                        val nameTv = TextView(this@FloatingMenuService).apply {
                            text = macro.name
                            setTextColor(0xFFFFFFFF.toInt())
                            textSize = 13f
                        }
                        val infoTv = TextView(this@FloatingMenuService).apply {
                            text = "${macro.gestures.size} gestures | ${formatDuration(macro.durationMs)} | ${macro.deviceWidth}x${macro.deviceHeight}"
                            setTextColor(0xFFAAAAAA.toInt())
                            textSize = 10f
                        }
                        row.addView(nameTv)
                        row.addView(infoTv)
                        val mid = macro.id
                        row.setOnClickListener {
                            selectedMacroId = mid
                            updateMacroSelection(container, mid)
                        }
                        container.addView(row)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FloatingMenu", "loadMacroList failed", e)
            }
        }
    }

    private fun updateMacroSelection(container: LinearLayout, selectedId: Long) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child?.setBackgroundColor(
                if (child is LinearLayout) {
                    val row = child as LinearLayout
                    val nameTv = row.getChildAt(0) as? TextView
                    val name = nameTv?.text?.toString() ?: ""
                    val macros = loadMacrosSync()
                    val isSelected = macros.any { it.id == selectedId && it.name == name }
                    if (isSelected) 0xFF1A3A2E.toInt() else 0x00000000
                } else 0x00000000
            )
        }
    }

    private fun loadMacrosSync(): List<Macro> {
        return try {
            kotlinx.coroutines.runBlocking {
                ConfigRepository.from(this@FloatingMenuService).loadMacros()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun stopService() {
        try {
            if (isBuilding) {
                isBuilding = false
                hideBuilderOverlay()
            }
            if (isPlaying) {
                macroPlayer?.stop()
                macroPlayer = null
                isPlaying = false
            }
            floatingView?.let { windowManager.removeView(it) }
            floatingView = null
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun showToast(msg: String) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000) % 60
        val m = (ms / 60000)
        return String.format(Locale.US, "%dm %02ds", m, s)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            hideBuilderOverlay()
            floatingView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        floatingView = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, FloatingMenuService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingMenuService::class.java))
        }
    }
}
