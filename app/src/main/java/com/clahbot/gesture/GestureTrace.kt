package com.clahbot.gesture

import android.util.Log
import com.clahbot.data.AppEventLog

/**
 * Per-gesture Logcat trace, tag `GESTURE`.
 *
 * One line is written for every gesture command that is allowed to touch the
 * screen, whether it completed, was cancelled, expired or was blocked before
 * dispatch. The line names the component and reason that asked for the gesture
 * so a device recording can answer "who tapped this?" without guesswork.
 *
 * Control commands (wait / cancel-all) are not gestures and are never logged.
 * Plain JVM tests have no Android logging, so a trace must never be the reason
 * a gesture fails: [Log.i] is wrapped in [runCatching].
 *
 * Example line:
 * `t=1731000000000 auth=1234567 component=AutoWallsDevice reason=scan-scroll
 *  type=swipe coords=(1360,1850)->(1360,1034) 350ms result=completed elapsed=412ms`
 */
object GestureTrace {

    const val TAG = "GESTURE"

    /**
     * Prepare the persistent log. The GESTURE lines are mirrored into the
     * app's single event log (`files/autoclash_events.log`), which is already
     * initialised by the service; this hook exists so a caller can make sure
     * the log exists before the first gesture is dispatched.
     */
    @JvmStatic
    fun init(context: android.content.Context) {
        AppEventLog.ensureInitialised(context)
    }

    /** Logs a command that was routed through [GestureAuthority]. */
    fun logCommand(
        command: GestureCommand,
        result: GestureResult,
        elapsedMs: Long,
        authorityHash: Int
    ) {
        when (val kind = command.kind) {
            is GestureCommand.Kind.Tap -> log(
                component = command.owner,
                reason = command.reason,
                type = "tap",
                coords = "(${kind.box.centerX},${kind.box.centerY})",
                result = resultName(result),
                elapsedMs = elapsedMs,
                authorityHash = authorityHash
            )
            is GestureCommand.Kind.Swipe -> log(
                component = command.owner,
                reason = command.reason,
                type = "swipe",
                coords = "(${kind.fromX},${kind.fromY})->(${kind.toX},${kind.toY})" +
                    " ${kind.durationMs}ms",
                result = resultName(result),
                elapsedMs = elapsedMs,
                authorityHash = authorityHash
            )
            GestureCommand.Kind.GlobalBack -> log(
                component = command.owner,
                reason = command.reason,
                type = "back",
                coords = "(system)",
                result = resultName(result),
                elapsedMs = elapsedMs,
                authorityHash = authorityHash
            )
            is GestureCommand.Kind.Wait, GestureCommand.Kind.CancelAll -> Unit
        }
    }

    /**
     * Logs a gesture dispatched outside [GestureAuthority] (a macro stroke or a
     * scan scroll while no authority is bound). There is no authority hash
     * because no authority was involved — the line says `direct` instead.
     */
    fun logDirect(
        component: String,
        reason: String,
        type: String,
        coords: String,
        result: String,
        elapsedMs: Long = 0L
    ) {
        log(
            component = component,
            reason = reason,
            type = type,
            coords = coords,
            result = result,
            elapsedMs = elapsedMs,
            authorityHash = null
        )
    }

    private fun resultName(result: GestureResult): String = when (result) {
        GestureResult.Completed -> "completed"
        GestureResult.Cancelled -> "cancelled"
        GestureResult.Expired -> "expired"
        GestureResult.Blocked -> "blocked"
        is GestureResult.Failed -> "failed:${result.reason}"
    }

    private fun log(
        component: String,
        reason: String,
        type: String,
        coords: String,
        result: String,
        elapsedMs: Long,
        authorityHash: Int?
    ) {
        val line = buildString {
            append("t=")
            append(System.currentTimeMillis())
            append(" route=")
            append(if (authorityHash == null) "direct" else "authority")
            if (authorityHash != null) {
                append(" auth=")
                append(authorityHash)
            }
            append(" component=")
            append(component.ifBlank { "unknown" })
            append(" reason=")
            append(reason.ifBlank { "-" })
            append(" type=")
            append(type)
            append(" coords=")
            append(coords)
            append(" result=")
            append(result)
            append(" elapsed=")
            append(elapsedMs)
            append("ms")
        }
        runCatching { Log.i(TAG, line) }
        // Persistent mirror — survives logcat rollover. It goes into the app's
        // single event log so there is exactly one file to pull from the
        // device: `adb exec-out run-as com.clahbot cat files/autoclash_events.log`.
        runCatching { AppEventLog.log(line) }
    }
}
