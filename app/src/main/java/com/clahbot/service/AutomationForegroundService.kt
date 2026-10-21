package com.clahbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.clahbot.R
import com.clahbot.data.models.Macro
import com.clahbot.execution.MacroPlayer
import com.clahbot.overlay.FloatingDashboard
import com.clahbot.ui.MainActivity
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AutomationForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var macroPlayer: MacroPlayer? = null
    private var floatingDashboard: FloatingDashboard? = null
    private var currentMacroJson: String? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            acquireWakeLock()
            floatingDashboard = FloatingDashboard(this)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> {
                    currentMacroJson = intent.getStringExtra(EXTRA_MACRO_JSON)
                    handleStart()
                }
                ACTION_PAUSE -> {
                    macroPlayer?.pause()
                    updateNotification("Paused")
                }
                ACTION_STOP -> {
                    stopAutomation()
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed: ${intent?.action}", e)
            stopAutomation()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        val projection = MediaProjectionHolder.take()
            ?: run {
                Log.e(TAG, "No valid MediaProjection token")
                stopSelf()
                return
            }

        val (resultCode, data) = projection
        startForegroundWithType()
        startCapture(resultCode, data)
        startMacroPlayback()
        floatingDashboard?.show()
    }

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }

        if (screenWidth <= 0 || screenHeight <= 0) {
            throw IllegalStateException("Invalid screen dimensions: ${screenWidth}x$screenHeight")
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection is null")

        mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                Handler(Looper.getMainLooper()).post {
                    stopAutomation()
                    stopSelf()
                }
            }
        }, Handler(Looper.getMainLooper()))

        captureThread = HandlerThread("ScreenCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bitmap = imageToBitmap(image)
                image.close()
                if (bitmap != null) {
                    val old = FrameCaptureHolder.latestFrame
                    FrameCaptureHolder.latestFrame = bitmap
                    old?.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame capture error", e)
            }
        }, captureHandler)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ClahBOT-Capture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        ) ?: throw IllegalStateException("VirtualDisplay creation failed")
    }

    private fun startMacroPlayback() {
        if (currentMacroJson == null) {
            Log.w(TAG, "No macro JSON provided")
            return
        }
        try {
            val gson = GsonBuilder().create()
            val macro = gson.fromJson(currentMacroJson, Macro::class.java)
            macroPlayer = MacroPlayer(screenWidth, screenHeight)
            macroPlayer?.play(macro, scope = serviceScope)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start macro playback", e)
        }
    }

    private fun stopAutomation() {
        try {
            macroPlayer?.stop()
            macroPlayer = null
            floatingDashboard?.hide()
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
            FrameCaptureHolder.swap()?.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "stopAutomation error", e)
        } finally {
            releaseWakeLock()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            if (planes.isEmpty()) return null
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            if (screenWidth <= 0 || screenHeight <= 0) return null
            val rowPadding = (rowStride - pixelStride * screenWidth).coerceAtLeast(0)
            val rawWidth = screenWidth + rowPadding / pixelStride.coerceAtLeast(1)
            if (rawWidth <= 0 || screenHeight <= 0) return null
            val rawBitmap = Bitmap.createBitmap(rawWidth, screenHeight, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
            if (rawBitmap !== cropped) rawBitmap.recycle()
            cropped
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap failed: ${e.message}", e)
            null
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClahBOT::Automation").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notification_channel_desc) }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String = getString(R.string.notification_text)): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pauseIntent = PendingIntent.getService(this, 1, Intent(this, AutomationForegroundService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 3, Intent(this, AutomationForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompatBuilder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_pause), pauseIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAutomation()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ClahBOT.Service"
        const val ACTION_START = "com.clahbot.ACTION_START"
        const val ACTION_PAUSE = "com.clahbot.ACTION_PAUSE"
        const val ACTION_STOP = "com.clahbot.ACTION_STOP"
        const val EXTRA_MACRO_JSON = "macro_json"
        private const val CHANNEL_ID = "clahbot_automation"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            if (!MediaProjectionHolder.hasValidToken()) {
                Log.e(TAG, "Cannot start: missing MediaProjection token")
                return
            }
            val intent = Intent(context, AutomationForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun start(context: Context, macroJson: String) {
            if (!MediaProjectionHolder.hasValidToken()) {
                Log.e(TAG, "Cannot start: missing MediaProjection token")
                return
            }
            val intent = Intent(context, AutomationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MACRO_JSON, macroJson)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AutomationForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}

private object NotificationCompatBuilder {
    fun build(context: Context, channelId: String): androidx.core.app.NotificationCompat.Builder {
        return androidx.core.app.NotificationCompat.Builder(context, channelId)
    }
    operator fun invoke(context: Context, channelId: String): androidx.core.app.NotificationCompat.Builder {
        return build(context, channelId)
    }
}
