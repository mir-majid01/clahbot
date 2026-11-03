package com.clahbot.game

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Injects taps and global actions into the game from the recovery watchdog
 * and the wall upgrader. Tap coordinates are frame pixels; they are mapped
 * through the captured window bounds before dispatch.
 */
object GameInput {

    private const val TAP_MS = 40L
    private const val TAP_TIMEOUT_MS = 1_500L

    fun toScreen(frameX: Int, frameY: Int, frame: ClahAccessibilityService.GameFrame): Point {
        val bounds = frame.windowBounds
            ?: return Point(frameX, frameY)
        val bitmap = frame.bitmap
        if (bitmap.width <= 0 || bitmap.height <= 0) return Point(frameX, frameY)
        val x = bounds.left + frameX * bounds.width() / bitmap.width
        val y = bounds.top + frameY * bounds.height() / bitmap.height
        return Point(x, y)
    }

    fun boxCentre(box: ScreenBox): Point =
        Point(box.centerX, box.centerY)

    suspend fun tap(screenX: Int, screenY: Int): Boolean {
        val service = ClahAccessibilityService.instance ?: return false
        val path = Path().apply {
            moveTo(screenX.toFloat(), screenY.toFloat())
            lineTo(screenX.toFloat(), screenY.toFloat())
        }
        val description = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MS))
                .build()
        } catch (_: Exception) {
            return false
        }
        return withTimeoutOrNull(TAP_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                val accepted = service.dispatchGesture(description, callback, null)
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        } ?: false
    }

    suspend fun tap(frameX: Int, frameY: Int, frame: ClahAccessibilityService.GameFrame): Boolean {
        val screen = toScreen(frameX, frameY, frame)
        return tap(screen.x, screen.y)
    }

    suspend fun tapBox(box: ScreenBox, frame: ClahAccessibilityService.GameFrame): Boolean {
        val centre = boxCentre(box)
        return tap(centre.x, centre.y, frame)
    }

    /** BACK as the least risky dismissal, only ever used on non-Clash windows. */
    suspend fun globalBack(): Boolean = withContext(Dispatchers.Main.immediate) {
        val service = ClahAccessibilityService.instance ?: return@withContext false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@withContext false
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            .getOrDefault(false)
    }
}
