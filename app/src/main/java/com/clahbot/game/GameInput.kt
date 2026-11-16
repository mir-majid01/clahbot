package com.clahbot.game

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import com.clahbot.data.models.GesturePath
import com.clahbot.data.models.GesturePoint
import com.clahbot.gesture.GestureAuthority
import com.clahbot.gesture.CancelToken
import com.clahbot.gesture.GestureCommand
import com.clahbot.gesture.GesturePriority
import com.clahbot.gesture.GestureResult
import com.clahbot.gesture.ScreenOwnership
import com.clahbot.results.ScreenHash
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Injects taps, swipes and global actions into the game. All screen-touching
 * dispatch goes through [GestureAuthority]; this class only converts coordinates
 * and builds command metadata.
 *
 * Tap coordinates are frame pixels; they are mapped through the captured window
 * bounds before dispatch.
 */
class GameInput(
    private val gestureAuthority: GestureAuthority,
    private val sessionId: Long,
    private var transactionId: Long = 0L
) {

    fun toScreen(frameX: Int, frameY: Int, frame: ClahAccessibilityService.GameFrame): Point =
        Companion.toScreen(frameX, frameY, frame)

    fun boxCentre(box: ScreenBox): Point =
        Point(box.centerX, box.centerY)

    /**
     * Tap a box on the current frame, dispatched through GestureAuthority with
     * WALL_UPGRADE priority and EXCLUSIVE screen ownership.
     *
     * Passes the current [ScreenObservation] so preflight can check frame-hash
     * freshness and forbidden-region overlap before the tap fires.
     */
    suspend fun tapBox(
        box: ScreenBox,
        frame: ClahAccessibilityService.GameFrame,
        observation: com.clahbot.game.ScreenObservation? = null,
        stableTransactionId: Long? = null,
        owner: String = "GameInput",
        reason: String = ""
    ): Boolean {
        val txnId = stableTransactionId ?: ++transactionId
        val command = GestureCommand(
            kind = GestureCommand.Kind.Tap(box),
            sessionId = sessionId,
            transactionId = txnId,
            frameHash = ScreenHash.fromBitmap(frame.bitmap),
            captureTimestampMillis = frame.capturedAtMillis,
            expiresAtMillis = System.currentTimeMillis() + 5_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken(),
            owner = owner,
            reason = reason
        )
        val result = gestureAuthority.enqueueAndAwait(command, observation)
        return result == GestureResult.Completed
    }

    /**
     * Swipe in screen coordinates, used to scroll the wall upgrade list.
     * Dispatched through GestureAuthority with WALL_UPGRADE priority and
     * EXCLUSIVE screen ownership.
     */
    suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
        frameHash: String,
        captureTimestampMillis: Long,
        observation: com.clahbot.game.ScreenObservation? = null,
        stableTransactionId: Long? = null,
        owner: String = "GameInput",
        reason: String = ""
    ): Boolean {
        val txnId = stableTransactionId ?: ++transactionId
        val command = GestureCommand(
            kind = GestureCommand.Kind.Swipe(fromX, fromY, toX, toY, durationMs),
            sessionId = sessionId,
            transactionId = txnId,
            frameHash = frameHash,
            captureTimestampMillis = captureTimestampMillis,
            expiresAtMillis = System.currentTimeMillis() + 5_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken(),
            owner = owner,
            reason = reason
        )
        val result = gestureAuthority.enqueueAndAwait(command, observation)
        return result == GestureResult.Completed
    }

    /** BACK as the least risky dismissal, only ever used on non-Clash windows.
     * Goes through GestureAuthority so it respects exclusive wall-upgrade
     * ownership and manual Stop cancellation. */
    suspend fun globalBack(owner: String = "GameInput", reason: String = ""): Boolean {
        transactionId++
        val command = GestureCommand(
            kind = GestureCommand.Kind.GlobalBack,
            sessionId = sessionId,
            transactionId = transactionId,
            frameHash = "",
            captureTimestampMillis = 0L,
            expiresAtMillis = System.currentTimeMillis() + 5_000,
            priority = GesturePriority.SHARED,
            screenOwnership = ScreenOwnership.SHARED,
            cancelToken = CancelToken(),
            owner = owner,
            reason = reason
        )
        val result = gestureAuthority.enqueueAndAwait(command, null)
        return result == GestureResult.Completed
    }

    companion object {
        /** Utility: map frame pixels to screen coords without a GameInput instance. */
        fun toScreen(frameX: Int, frameY: Int, frame: ClahAccessibilityService.GameFrame): Point {
            val bounds = frame.windowBounds
                ?: return Point(frameX, frameY)
            val bitmap = frame.bitmap
            if (bitmap.width <= 0 || bitmap.height <= 0) return Point(frameX, frameY)
            val x = bounds.left + frameX * bounds.width() / bitmap.width
            val y = bounds.top + frameY * bounds.height() / bitmap.height
            return Point(x, y)
        }

        /** Build a GestureDescription from a complex path (MacroPlayer replay). */
        fun buildDescription(paths: List<GesturePath>, gestureType: String): GestureDescription? {
            if (paths.isEmpty()) return null
            try {
                val builder = GestureDescription.Builder()
                for (path in paths) {
                    builder.addStroke(
                        GestureDescription.StrokeDescription(
                            buildPath(path.points, gestureType),
                            path.startOffsetMs,
                            path.durationMs.coerceAtLeast(1L)
                        )
                    )
                }
                return builder.build()
            } catch (_: Exception) {
                return null
            }
        }

        private fun buildPath(points: List<GesturePoint>, type: String): Path {
            val path = Path()
            val first = points.first()
            path.moveTo(first.x, first.y)
            if (type != "swipe" || points.size < 2) return path
            for (i in 1 until points.size) {
                path.lineTo(points[i].x, points[i].y)
            }
            return path
        }
    }
}