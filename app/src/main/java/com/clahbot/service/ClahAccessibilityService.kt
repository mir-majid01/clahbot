package com.clahbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ClahAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clearTouchExploration()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore our own overlay windows; only remember other apps.
            if (pkg != packageName) foregroundPackage = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private suspend fun captureDisplayScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
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
                                    continuation.resume(copy)
                                } else {
                                    copy?.recycle()
                                }
                            } catch (throwable: Throwable) {
                                Log.w(TAG, "Could not convert screenshot", throwable)
                                if (continuation.isActive) continuation.resume(null)
                            } finally {
                                runCatching { buffer.close() }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Accessibility screenshot failed: $errorCode")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    try {
                        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
                    } catch (throwable: Throwable) {
                        Log.w(TAG, "takeScreenshot rejected", throwable)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
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
         */
        fun currentForegroundPackage(): String? {
            val service = instance ?: return null
            val windows = try {
                service.windows
            } catch (_: Exception) {
                null
            } ?: return null
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
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

        suspend fun captureScreenshot(): Bitmap? {
            return try {
                instance?.captureDisplayScreenshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.w(TAG, "Accessibility screenshot unavailable", throwable)
                null
            }
        }

        private const val TAG = "AutoClashAccessibility"
        private const val SCREENSHOT_TIMEOUT_MS = 3_000L
    }
}
