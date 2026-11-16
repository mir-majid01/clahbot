package com.clahbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.clahbot.data.AppEventLog
import com.clahbot.gesture.GestureAuthority
import com.clahbot.gesture.GestureTrace
import com.clahbot.session.GameSessionTracker
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ClahAccessibilityService : AccessibilityService() {

    data class CaptureShot(
        val bitmap: Bitmap,
        val method: String,
        val windowPackage: String?,
        val windowId: Int?
    )

    /** A captured game frame plus the window's on-screen bounds, so frame
     *  pixel coordinates can be mapped to screen coordinates for taps. */
    data class GameFrame(
        val bitmap: Bitmap,
        val windowBounds: Rect?,
        val windowPackage: String?,
        /** System.currentTimeMillis() when this frame was captured. */
        val capturedAtMillis: Long = System.currentTimeMillis()
    )

    private fun gameWindowBounds(): Rect? {
        val window = gameWindow() ?: return null
        val bounds = Rect()
        return try {
            window.getBoundsInScreen(bounds)
            bounds
        } catch (_: Exception) {
            null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppEventLog.log("accessibility connected")
        // Bind the ONE shared gesture authority to this accessibility service.
        // Until this happens the authority has no way to dispatch anything, so
        // every gesture path must go through it.
        runCatching { GestureAuthority.shared().bind(this) }
            .onFailure { Log.w(TAG, "Could not bind shared gesture authority", it) }
        runCatching { clearTouchExploration() }
            .onFailure { Log.w(TAG, "Could not clear touch exploration", it) }
        runCatching { GameSessionTracker.start(this) }
            .onFailure {
                // A broken data file must not take the accessibility service
                // down; recording and playback would then vanish from Settings.
                Log.e(TAG, "Session tracker start failed", it)
                AppEventLog.log("accessibility init: session tracker failed: ${it.message}")
            }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = event.packageName?.toString() ?: return
                // Ignore our own overlay windows; only remember other apps.
                if (pkg != packageName) foregroundPackage = pkg
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Accessibility event ignored", error)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        // The shared authority must not keep dispatching into a service that
        // is gone; unbinding also cancels everything still pending.
        runCatching { GestureAuthority.shared().unbind() }
        AppEventLog.log("accessibility unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        runCatching { GestureAuthority.shared().unbind() }
        AppEventLog.log("accessibility destroyed")
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureDisplayScreenshot(): Bitmap? {
        return captureDisplayScreenshotWithReason().first
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureDisplayScreenshotWithReason(): Pair<Bitmap?, Int?> {
        return withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Pair<Bitmap?, Int?>> { continuation ->
                    var error: Int? = null
                    val callback = object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            try {
                                val wrapped = Bitmap.wrapHardwareBuffer(
                                    buffer,
                                    result.colorSpace
                                )
                                val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                wrapped?.recycle()
                                if (continuation.isActive) {
                                    continuation.resume(copy to null)
                                } else {
                                    copy?.recycle()
                                }
                            } catch (throwable: Throwable) {
                                Log.w(TAG, "Could not convert screenshot", throwable)
                                if (continuation.isActive) continuation.resume(null to null)
                            } finally {
                                runCatching { buffer.close() }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            error = errorCode
                            Log.w(TAG, "Accessibility screenshot failed: $errorCode")
                            if (continuation.isActive) continuation.resume(null to errorCode)
                        }
                    }
                    try {
                        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                    } catch (throwable: Throwable) {
                        Log.w(TAG, "takeScreenshot rejected", throwable)
                        if (continuation.isActive) continuation.resume(null to null)
                    }
                }
            } ?: (null to null)
        }
    }

    /**
     * Captures only the game's application window on Android 14+, so the
     * floating controller is never in the frame and never has to be hidden.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun captureWindowScreenshot(
        windowId: Int
    ): Pair<Bitmap?, Int?> = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Pair<Bitmap?, Int?>> { continuation ->
                val callback = object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            if (continuation.isActive) {
                                continuation.resume(copy to null)
                            } else {
                                copy?.recycle()
                            }
                        } catch (throwable: Throwable) {
                            Log.w(TAG, "Could not convert window screenshot", throwable)
                            if (continuation.isActive) continuation.resume(null to null)
                        } finally {
                            runCatching { buffer.close() }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Window screenshot failed: $errorCode")
                        if (continuation.isActive) continuation.resume(null to errorCode)
                    }
                }
                try {
                    takeScreenshotOfWindow(windowId, mainExecutor, callback)
                } catch (throwable: Throwable) {
                    Log.w(TAG, "takeScreenshotOfWindow rejected", throwable)
                    if (continuation.isActive) continuation.resume(null to null)
                }
            }
        } ?: (null to null)
    }

    private fun gameWindow(): AccessibilityWindowInfo? {
        val windows = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return null
        return windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstOrNull { window ->
                val pkg = try {
                    window.root?.packageName?.toString()
                } catch (_: Exception) {
                    null
                }
                pkg?.startsWith("com.supercell") == true
            }
    }

    private fun clearTouchExploration() {
        val info = serviceInfo ?: return
        val touched = (info.flags and
            AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0
        if (!touched) return
        info.flags = info.flags and
            AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        info.eventTypes = info.eventTypes and
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END.inv()
        try {
            setServiceInfo(info)
        } catch (t: Throwable) {
            Log.w("AutoClashLiveRelay", "Could not clear touch exploration", t)
        }
    }

    companion object {
        @Volatile
        var instance: ClahAccessibilityService? = null
            private set

        @Volatile
        private var foregroundPackage: String? = null

        fun isEnabled(): Boolean = instance != null

        /**
         * Reads the focused application window right now. Our own overlay is a
         * TYPE_SYSTEM window, so filtering to TYPE_APPLICATION skips the
         * controller instead of mistaking it for the foreground app.
         *
         * Some devices still surface the overlay as an application window; on
         * those the focused app resolved to `com.clahbot` and scans were
         * refused with "Clash of Clans must be on screen" while the game was
         * visibly open. Any window rooted in our own package is therefore
         * ignored: it can never be the game the scanner needs.
         */
        private const val SELF_PACKAGE = "com.clahbot"

        fun currentForegroundPackage(): String? {
            val service = instance ?: return null
            val windows = try {
                service.windows
            } catch (_: Exception) {
                null
            } ?: return null
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .filter { app ->
                    try {
                        app.root?.packageName?.toString() != SELF_PACKAGE
                    } catch (_: Exception) {
                        true
                    }
                }
            val window = apps.firstOrNull { it.isActive }
                ?: apps.firstOrNull { it.isFocused }
                ?: apps.firstOrNull()
                ?: return null
            return try {
                window.root?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Unknown is treated as the game: a stale or missing signal must not
         * trap playback in a permanent pause.
         */
        fun isGameForeground(): Boolean {
            val pkg = currentForegroundPackage() ?: foregroundPackage ?: return true
            return pkg.startsWith("com.supercell")
        }

        fun foregroundLabel(): String =
            currentForegroundPackage() ?: foregroundPackage ?: "another app"

        /**
         * Set by the floating service. Only used by the display-capture
         * fallback on Android 13 and older; the overlay is masked out of the
         * frame instead of being hidden, so the screen never flickers.
         */
        @Volatile
        var overlayMaskProvider: () -> List<Rect> = { emptyList() }

        suspend fun captureShot(): CaptureShot? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val service = instance ?: return null
                val window = withContext(Dispatchers.Main.immediate) {
                    runCatching { service.gameWindow() }.getOrNull()
                }
                if (window != null) {
                    val (bitmap, error) = service.captureWindowScreenshot(window.id)
                    if (bitmap != null) {
                        return CaptureShot(
                            bitmap = bitmap,
                            method = "window",
                            windowPackage = currentForegroundPackage(),
                            windowId = window.id
                        )
                    }
                    AppEventLog.log(
                        "window screenshot failed id=${window.id} code=${error ?: "null"}; " +
                            "falling back to display capture"
                    )
                } else {
                    AppEventLog.log("window screenshot skipped: no game window")
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val service = instance ?: return null
            val (bitmap, error) = service.captureDisplayScreenshotWithReason()
            if (bitmap == null) {
                AppEventLog.log("display screenshot failed code=${error ?: "null"}")
                return null
            }
            val masks = overlayMaskProvider()
            if (masks.isEmpty()) {
                return CaptureShot(bitmap, "display", currentForegroundPackage(), null)
            }
            // Bitmap.copy(..., false) and hardware-bitmap copies can be
            // immutable; Canvas requires a mutable software bitmap.
            if (!bitmap.isMutable) {
                val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                bitmap.recycle()
                if (mutable == null) {
                    return null
                }
                return CaptureShot(maskOverlay(mutable, masks), "display+mask", currentForegroundPackage(), null)
            }
            return CaptureShot(maskOverlay(bitmap, masks), "display+mask", currentForegroundPackage(), null)
        }

        private fun maskOverlay(bitmap: Bitmap, masks: List<Rect>): Bitmap {
            val canvas = Canvas(bitmap)
            val paint = android.graphics.Paint().apply {
                color = Color.rgb(10, 16, 29)
                style = android.graphics.Paint.Style.FILL
            }
            for (rect in masks) {
                if (!rect.isEmpty) canvas.drawRect(rect, paint)
            }
            return bitmap
        }

        suspend fun captureScreenshot(): Bitmap? = captureShot()?.bitmap

        /**
         * One controlled vertical swipe on the live game window, used by the
         * read-only wall-list scanner. It is deliberately separate from
         * GestureAuthority so the scanner can move the list without enabling
         * wall-upgrade taps: no tap path calls this. Returns false when the
         * service is gone or the system refuses the gesture.
         */
        suspend fun swipeOnScreen(
            fromX: Int,
            fromY: Int,
            toX: Int,
            toY: Int,
            durationMs: Long,
            owner: String = "ClahAccessibilityService",
            reason: String = "swipe"
        ): Boolean {
            val service = instance
            if (service == null) {
                GestureTrace.logDirect(
                    component = owner,
                    reason = reason,
                    type = "swipe",
                    coords = "($fromX,$fromY)->($toX,$toY) ${durationMs}ms",
                    result = "failed:service unavailable"
                )
                return false
            }
            val safeDuration = durationMs.coerceAtLeast(1L)
            val startedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.Main.immediate) {
                withTimeoutOrNull(safeDuration + 2_000L) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        val path = Path().apply {
                            moveTo(fromX.toFloat(), fromY.toFloat())
                            lineTo(toX.toFloat(), toY.toFloat())
                        }
                        val gesture = try {
                            GestureDescription.Builder()
                                .addStroke(
                                    GestureDescription.StrokeDescription(path, 0L, safeDuration)
                                )
                                .build()
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resume(false)
                            return@suspendCancellableCoroutine
                        }
                        val callback = object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                        val accepted = runCatching {
                            service.dispatchGesture(gesture, callback, null)
                        }.getOrDefault(false)
                        if (!accepted && continuation.isActive) continuation.resume(false)
                    }
                } ?: false
            }
            // This path bypasses GestureAuthority on purpose, so it must log its
            // own GESTURE line: a scan scroll is a screen touch too.
            GestureTrace.logDirect(
                component = owner,
                reason = reason,
                type = "swipe",
                coords = "($fromX,$fromY)->($toX,$toY) ${safeDuration}ms",
                result = if (result) "completed" else "failed:not accepted",
                elapsedMs = System.currentTimeMillis() - startedAt
            )
            return result
        }

        /**
         * The frame the recovery watchdog and the wall upgrader act on: the
         * game window when it exists, else the masked display. The window
         * bounds travel with the frame so taps can be mapped to the screen.
         */
        suspend fun captureGameFrame(): GameFrame? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val service = instance ?: return null
                val bounds = withContext(Dispatchers.Main.immediate) {
                    runCatching { service.gameWindowBounds() }.getOrNull()
                }
                val window = withContext(Dispatchers.Main.immediate) {
                    runCatching { service.gameWindow() }.getOrNull()
                }
                if (window != null) {
                    val (bitmap, _) = service.captureWindowScreenshot(window.id)
                    if (bitmap != null) {
                        return GameFrame(
                            bitmap = bitmap,
                            windowBounds = bounds,
                            windowPackage = currentForegroundPackage()
                        )
                    }
                }
            }
            val shot = captureShot() ?: return null
            return GameFrame(
                bitmap = shot.bitmap,
                windowBounds = null,
                windowPackage = shot.windowPackage
            )
        }

        private const val TAG = "AutoClashAccessibility"
        private const val SCREENSHOT_TIMEOUT_MS = 3_000L
    }
}
