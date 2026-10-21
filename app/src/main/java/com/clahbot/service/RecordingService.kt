package com.clahbot.service

import android.accessibilityservice.GestureDescription
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.clahbot.R
import com.clahbot.data.ConfigRepository
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class RecordingService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var controlPanel: View? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private val gestures = mutableListOf<RecordedGesture>()
    private var recordingStartTime = 0L
    private var isPaused = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var touchDownNormX = 0f
    private var touchDownNormY = 0f
    private var touchDownTime = 0L
    private var isTouching = false
    private val movePoints = mutableListOf<Pair<Float, Float>>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var timerActive = true

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        screenWidth = intent?.getIntExtra(EXTRA_WIDTH, 0) ?: 0
        screenHeight = intent?.getIntExtra(EXTRA_HEIGHT, 0) ?: 0
        if (screenWidth <= 0 || screenHeight <= 0) {
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
        }
        showOverlay()
        recordingStartTime = System.currentTimeMillis()
        startTimerUpdates()
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_recording, null)
        controlPanel = view.findViewById(R.id.recordingPanel)
        windowManager.addView(view, params)
        overlayView = view

        view.findViewById<Button>(R.id.btnRecordingFinish).setOnClickListener { finishRecording() }
        view.findViewById<Button>(R.id.btnRecordingCancel).setOnClickListener { cancelRecording() }
        view.findViewById<Button>(R.id.btnRecordingPause).setOnClickListener { togglePause() }

        view.setOnTouchListener { _, event -> handleTouch(event) }
        view.isClickable = true
        view.isFocusable = true
    }

    private fun isInsideControlPanel(rawX: Float, rawY: Float): Boolean {
        val panel = controlPanel ?: return false
        val loc = IntArray(2)
        panel.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + panel.width
        val bottom = top + panel.height
        return rawX in left.toFloat()..right.toFloat() && rawY in top.toFloat()..bottom.toFloat()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (isInsideControlPanel(event.rawX, event.rawY)) return false

        val now = System.currentTimeMillis() - recordingStartTime
        val normX = event.x / screenWidth
        val normY = event.y / screenHeight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownRawX = event.x
                touchDownRawY = event.y
                touchDownNormX = normX
                touchDownNormY = normY
                touchDownTime = now
                isTouching = true
                movePoints.clear()
                movePoints.add(Pair(event.x, event.y))
            }
            MotionEvent.ACTION_MOVE -> {
                if (isTouching) {
                    movePoints.add(Pair(event.x, event.y))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isTouching) {
                    val durationMs = now - touchDownTime
                    val dx = (normX - touchDownNormX) * screenWidth
                    val dy = (normY - touchDownNormY) * screenHeight
                    val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                    val gestureType = when {
                        durationMs > 500 && distance < 30f -> "long_press"
                        distance > 30f -> "swipe"
                        else -> "tap"
                    }

                    gestures.add(RecordedGesture(
                        id = gestures.size + 1,
                        timestampMs = touchDownTime,
                        gestureType = gestureType,
                        durationMs = durationMs,
                        normalizedStartX = touchDownNormX,
                        normalizedStartY = touchDownNormY,
                        normalizedEndX = normX,
                        normalizedEndY = normY,
                        pressure = event.pressure
                    ))

                    if (!isPaused) {
                        forwardGesture(movePoints, gestureType, durationMs)
                    }

                    isTouching = false
                    movePoints.clear()
                    updateStepsCounter()
                }
            }
        }
        return true
    }

    private fun forwardGesture(points: List<Pair<Float, Float>>, type: String, originalDuration: Long) {
        val service = ClahAccessibilityService.instance ?: return
        if (points.isEmpty()) return
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first, points[i].second)
        }
        val duration = when (type) {
            "tap" -> 30L
            "long_press" -> originalDuration.coerceAtLeast(200)
            else -> originalDuration.coerceAtLeast(50)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun finishRecording() {
        timerActive = false
        val dateStr = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        val totalDuration = if (gestures.isNotEmpty()) gestures.last().timestampMs + gestures.last().durationMs else 0L

        val macro = Macro(
            id = System.currentTimeMillis(),
            name = "Recording $dateStr",
            createdAt = System.currentTimeMillis(),
            durationMs = totalDuration,
            deviceWidth = screenWidth,
            deviceHeight = screenHeight,
            orientation = if (screenWidth > screenHeight) "landscape" else "portrait",
            gestures = gestures.toList()
        )

        serviceScope.launch {
            ConfigRepository.from(this@RecordingService).saveMacro(macro)
        }

        cleanup()
        stopSelf()
    }

    private fun cancelRecording() {
        timerActive = false
        cleanup()
        stopSelf()
    }

    private fun togglePause() {
        isPaused = !isPaused
        val view = overlayView ?: return
        view.findViewById<Button>(R.id.btnRecordingPause).text = if (isPaused) "Resume" else "Pause"
        view.findViewById<TextView>(R.id.tvRecordingIndicator).text = if (isPaused) "● Paused" else "● Recording"
    }

    private fun updateStepsCounter() {
        val view = overlayView ?: return
        view.findViewById<TextView>(R.id.tvRecordingSteps).text = "Steps: ${gestures.size}"
    }

    private fun startTimerUpdates() {
        val r = object : Runnable {
            override fun run() {
                if (!timerActive) return
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val secs = (elapsed / 1000) % 60
                val mins = (elapsed / 60000) % 60
                val view = overlayView
                if (view != null) {
                    view.findViewById<TextView>(R.id.tvRecordingTimer).text =
                        String.format("%02d:%02d", mins, secs)
                }
                mainHandler.postDelayed(this, 500)
            }
        }
        mainHandler.post(r)
    }

    private fun cleanup() {
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WIDTH = "screen_width"
        const val EXTRA_HEIGHT = "screen_height"

        fun start(context: Context, width: Int, height: Int) {
            val intent = Intent(context, RecordingService::class.java).apply {
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
