package com.clahbot.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.clahbot.game.GameState
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

sealed class GestureResult {
    object Completed : GestureResult()
    object Cancelled : GestureResult()
    object Expired : GestureResult()
    object Blocked : GestureResult()
    data class Failed(val reason: String) : GestureResult()
}

sealed class PreflightResult {
    object Allowed : PreflightResult()
    data class Blocked(val reason: String) : PreflightResult()
    data class Cancel(val reason: String) : PreflightResult()
}

enum class GesturePriority { MANUAL, WALL_UPGRADE, ATTACK_MACRO, TRIGGER, SHARED }

enum class ScreenOwnership { EXCLUSIVE, SHARED, NONE }

class CancelToken {
    @Volatile
    private var cancelled = false
    fun cancel() { cancelled = true }
    val isCancelled: Boolean get() = cancelled
}

data class GestureCommand(
    val kind: Kind,
    val sessionId: Long,
    val transactionId: Long,
    val wallTxnId: Long? = null,
    val frameHash: String,
    val captureTimestampMillis: Long,
    val expiresAtMillis: Long,
    val priority: GesturePriority,
    val screenOwnership: ScreenOwnership,
    val cancelToken: CancelToken,
    val customDescription: GestureDescription? = null,
    /** Component that requested the gesture, for the GESTURE log line. */
    val owner: String = "unknown",
    /** Why the gesture was requested (e.g. "scan-scroll"), for the GESTURE log. */
    val reason: String = ""
) {
    val targetBox: ScreenBox?
        get() = when (kind) {
            is Kind.Tap -> kind.box
            else -> null
        }

    fun isScreenTouching(): Boolean = kind !is Kind.Wait && kind != Kind.CancelAll

    sealed class Kind {
        data class Tap(val box: ScreenBox) : Kind()
        data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long) : Kind()
        data class Wait(val durationMs: Long) : Kind()
        object CancelAll : Kind()
        object GlobalBack : Kind()
    }
}

/**
 * Maximum age of a captured screenshot before a command built from it is rejected.
 * Defaults to 2 seconds: a command older than this is too stale to trust.
 */
const val SCREENSHOT_MAX_AGE_MS = 2_000L

/**
 * Maximum time a command may wait in the park loop (gate held / paused) before
 * being rejected. Prevents a command from waiting forever.
 */
const val MAX_PARK_WAIT_MS = 30_000L

class GestureAuthority {

    /** The accessibility service that actually performs the dispatch. Bound when connected. */
    @Volatile
    private var service: AccessibilityService? = null

    /**
     * The Auto Walls "Allow tapping" switch, mirrored into the authority so the
     * rule is enforced at the SINGLE dispatch point instead of at every caller.
     *
     * While it is off (the production default) the only wall-pipeline command
     * that may touch the screen is the read-only list scroll owned by
     * [SCAN_SCROLL_OWNER]. Wall selection, upgrade, confirmation, Gem and
     * Goblin taps are refused right here and logged as a blocked GESTURE line,
     * even if a caller forgot to check the flag.
     *
     * Fail-closed: a fresh authority starts with tapping off.
     */
    @Volatile
    private var allowWallTapping = false

    /** Holds exclusive screen ownership while a wall-upgrade transaction runs. */
    @Volatile
    private var exclusiveOwner: Long? = null

    @Volatile
    private var gateHeld = false
    @Volatile
    private var paused = false

    /** The transaction currently being dispatched, if any. */
    @Volatile
    private var inFlight: Long? = null

    /** The wall-upgrade transaction that currently holds exclusive ownership. */
    @Volatile
    private var wallTxnId: Long? = null

    /** Pending commands waiting to be dispatched, keyed by transactionId. */
    private val pending = mutableMapOf<Long, GestureCommand>()

    /** Serializes dispatch: only one screen-touching command at a time. */
    private val mutex = Mutex()

    fun bind(service: AccessibilityService) {
        this.service = service
    }

    fun unbind() {
        service = null
        cancelAll()
    }

    fun setGateHeld(held: Boolean) { gateHeld = held }

    fun setPaused(paused: Boolean) { this.paused = paused }

    /**
     * Mirrors the Auto Walls "Allow tapping" switch into the dispatch gate.
     * `false` (the default) refuses every wall-pipeline tap at the dispatch
     * point and leaves only the read-only list scroll able to touch the screen.
     */
    fun setAllowWallTapping(allowed: Boolean) { allowWallTapping = allowed }

    /** True when the wall pipeline may dispatch a tap. */
    fun wallTappingAllowed(): Boolean = allowWallTapping

    /**
     * True for the read-only wall-list scroll: a swipe owned by
     * [SCAN_SCROLL_OWNER] whose reason starts with `scan-scroll`.
     *
     * This is the one screen-touching command that stays legal while
     * "Allow tapping" is off — it never selects, upgrades or confirms anything.
     */
    fun isScanScroll(command: GestureCommand): Boolean =
        command.kind is GestureCommand.Kind.Swipe &&
            command.owner == SCAN_SCROLL_OWNER &&
            command.reason.startsWith(SCAN_SCROLL_REASON_PREFIX)

    /**
     * True when the command belongs to the wall pipeline (Auto Walls scan,
     * Auto Walls upgrade, or a recorded wall macro).
     */
    private fun isWallPipeline(command: GestureCommand): Boolean =
        command.owner in WALL_PIPELINE_OWNERS ||
            command.priority == GesturePriority.WALL_UPGRADE

    /**
     * Manual stop: cancel every pending command, release exclusivity.
     * After this returns, no pending command will be dispatched.
     */
    fun cancelAll() {
        // Cancel every pending command's token so in-progress enqueueAndAwait
        // calls observe cancellation and return without dispatching.
        pending.values.forEach { it.cancelToken.cancel() }
        pending.clear()
        exclusiveOwner = null
        wallTxnId = null
    }

    /** Cancel a single pending transaction. */
    fun cancelTransaction(transactionId: Long) {
        pending.remove(transactionId)?.cancelToken?.cancel()
    }

    /** True while a gesture is being dispatched. */
    fun isInFlight(): Boolean = inFlight != null

    /** Number of commands currently waiting in the pending queue. */
    fun pendingCount(): Int = pending.size

    /** Call when a wall-upgrade transaction begins. Sets exclusive ownership.
     *  Does not replace an already-active transaction.
     *  @return true if ownership was acquired, false if another transaction is active. */
    fun startWallTxn(sessionId: Long): Boolean {
        if (wallTxnId != null) return false  // a transaction is already active
        wallTxnId = sessionId
        exclusiveOwner = sessionId
        return true
    }

    /** Call when a wall-upgrade transaction ends. Releases ownership.
     *  @return true if ownership was released, false if none was active. */
    fun endWallTxn(): Boolean {
        if (wallTxnId == null) return false
        wallTxnId = null
        exclusiveOwner = null
        return true
    }

    // ==================== preflight ====================

    /**
     * Check a command before dispatch. Runs under the [mutex] so ownership
     * changes are atomic with respect to other commands.
     *
     * Returns:
     *  - [Allowed] if the gesture may proceed;
     *  - [Blocked] if a safety condition forbids it;
     *  - [Cancel] if the command was cancelled or expired.
     */
    suspend fun preflightCheck(
        command: GestureCommand,
        observation: ScreenObservation?
    ): PreflightResult {
        // 1. Cancellation — checked first so a cancelled command never proceeds.
        if (command.cancelToken.isCancelled) {
            return PreflightResult.Cancel("command cancelled")
        }

        // 2. Expiry — command is too old to dispatch.
        val now = System.currentTimeMillis()
        if (now > command.expiresAtMillis) {
            return PreflightResult.Cancel("command expired")
        }

        // 3. Screenshot freshness — reject commands built from old screenshots.
        // Only applies when captureTimestampMillis is > 0 (real screenshot).
        // Attack macros use 0 and skip this check.
        if (command.captureTimestampMillis > 0L) {
            val age = now - command.captureTimestampMillis
            if (age > SCREENSHOT_MAX_AGE_MS) {
                return PreflightResult.Cancel(
                    "screenshot too old: ${age}ms (max ${SCREENSHOT_MAX_AGE_MS}ms)"
                )
            }
        }

        // 4. Frame hash — reject if the screen changed since the command was created.
        // Only applies when frameHash is non-empty.
        if (command.frameHash.isNotEmpty()) {
            val obsHash = observation?.hash ?: ""
            if (obsHash.isNotEmpty() && command.frameHash != obsHash) {
                return PreflightResult.Cancel(
                    "stale frame: screen changed since command was created"
                )
            }
        }

        // 4.5 Allow-Tapping-OFF — enforced at this single dispatch point so a
        // caller that forgets to check cannot slip a tap past the switch.
        // While tapping is off, the ONLY wall-pipeline command allowed to
        // touch the screen is the read-only list scroll. Wall selection,
        // upgrade, confirmation, Gem and Goblin taps are refused here.
        if (!allowWallTapping && isWallPipeline(command) && !isScanScroll(command)) {
            return PreflightResult.Blocked(
                "allow tapping is off: only the wall-list scan scroll may touch the screen"
            )
        }

        // 5. Exclusive screen ownership — wall transactions hold ownership continuously.
        if (command.screenOwnership == ScreenOwnership.EXCLUSIVE) {
            // Check if a wall transaction is already active.
            if (wallTxnId != null && wallTxnId != command.sessionId) {
                return PreflightResult.Blocked(
                    "screen owned by wall transaction ${wallTxnId}"
                )
            }
            if (command.sessionId != wallTxnId) {
                // This command claims exclusive ownership for the wall transaction.
                exclusiveOwner = command.sessionId
                wallTxnId = command.sessionId
            }
            // If the session already owns the wall txn, keep ownership.
        } else {
            val owner = exclusiveOwner
            if (owner != null && owner != command.sessionId) {
                return PreflightResult.Blocked(
                    "screen exclusively owned by session $owner"
                )
            }
        }

        // 5.5 Wall-list scan exclusivity — while the read-only scanner owns the
        // screen to scroll the list, no other automation may inject gestures.
        if (ScanGate.isActive() && !ScanGate.ownedBy(command.sessionId)) {
            return PreflightResult.Blocked("wall-list scan owns the screen")
        }

        // 6. Gate / pause.
        if (gateHeld) {
            return PreflightResult.Blocked("gate held by watchdog")
        }
        if (paused) {
            return PreflightResult.Blocked("playback paused")
        }

        // 7. Screen state — only dispatch on a Clash screen.
        if (observation != null) {
            if (observation.state == GameState.NOT_CLASH ||
                observation.state == GameState.UNKNOWN) {
                return PreflightResult.Blocked("not on Clash screen")
            }
        }

        // 8. Forbidden overlap — never tap a never-press box.
        val target = command.targetBox
        if (target != null && observation != null) {
            val overlap = observation.forbidden.any { it.intersects(target) }
            if (overlap) {
                return PreflightResult.Blocked("target overlaps a forbidden region")
            }
        }

        return PreflightResult.Allowed
    }

    // ==================== dispatch ====================

    /**
     * Serialize and dispatch one command. Suspends until the command completes,
     * is cancelled, expires, or is blocked by preflight.
     *
     * Only one screen-touching command is in flight at a time. Wait and
     * CancelAll are control commands.
     *
     * [observation] is the current screen classification; required for frame-hash
     * and forbidden-region checks on wall-upgrade commands. Attack macros may
     * pass null since they carry no frame hash.
     */
    suspend fun enqueueAndAwait(
        command: GestureCommand,
        observation: ScreenObservation? = null
    ): GestureResult {
        // Control commands: no screen dispatch.
        if (command.kind is GestureCommand.Kind.Wait) {
            delay(command.kind.durationMs.coerceAtLeast(0L))
            return GestureResult.Completed
        }
        if (command.kind == GestureCommand.Kind.CancelAll) {
            cancelAll()
            return GestureResult.Completed
        }

        // Register as pending so cancelAll() can cancel us.
        pending[command.transactionId] = command

        val startedAt = System.currentTimeMillis()
        val result = mutex.withLock {
            // Remove from pending — we are now the active dispatch.
            pending.remove(command.transactionId)

            // Re-check cancellation under the lock.
            if (command.cancelToken.isCancelled) {
                GestureResult.Cancelled
            } else {
                // Preflight: freshness, hash, ownership, gate, screen state,
                // forbidden overlap. Runs under the mutex for atomicity.
                val preflight = preflightCheck(command, observation)
                when (preflight) {
                    is PreflightResult.Cancel -> {
                        GestureResult.Cancelled
                    }
                    is PreflightResult.Blocked -> {
                        GestureResult.Failed("blocked: ${preflight.reason}")
                    }
                    PreflightResult.Allowed -> {
                        dispatchLocked(command)
                    }
                }
            }
        }
        // Exactly one GESTURE line per screen-touching command, including the
        // ones preflight refused: a refusal still proves nothing touched the
        // screen, which is what a safety review needs to see.
        GestureTrace.logCommand(
            command = command,
            result = result,
            elapsedMs = System.currentTimeMillis() - startedAt,
            authorityHash = System.identityHashCode(this)
        )
        return result
    }

    private suspend fun dispatchLocked(command: GestureCommand): GestureResult {
        // Park while gate is held or paused, with a maximum wait so no command
        // waits forever. Check cancellation on every poll.
        val parkDeadline = System.currentTimeMillis() + MAX_PARK_WAIT_MS
        while ((gateHeld || paused) && !command.cancelToken.isCancelled) {
            if (System.currentTimeMillis() > parkDeadline) {
                return GestureResult.Blocked
            }
            delay(50L)
        }
        if (command.cancelToken.isCancelled) return GestureResult.Cancelled

        val now = System.currentTimeMillis()
        if (now > command.expiresAtMillis) return GestureResult.Expired

        val service = service ?: return GestureResult.Failed("accessibility service unavailable")

        // Global BACK is a system action, not a gesture path.
        if (command.kind == GestureCommand.Kind.GlobalBack) {
            return dispatchGlobalBack(service, command)
        }

        val description: GestureDescription
        try {
            description = command.customDescription
                ?: command.kind.toGestureDescription()
        } catch (_: Exception) {
            return GestureResult.Failed("invalid gesture description")
        }

        inFlight = command.transactionId
        try {
            val timeoutMs = when (command.kind) {
                is GestureCommand.Kind.Swipe -> command.kind.durationMs.coerceAtLeast(1L) + 2_000L
                else -> 2_000L
            }

            val deferred = CompletableDeferred<Boolean>()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    // Only complete if not already cancelled/timed out.
                    if (!command.cancelToken.isCancelled) {
                        deferred.complete(true)
                    }
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (!command.cancelToken.isCancelled) {
                        deferred.complete(false)
                    }
                }
            }

            // If already cancelled before dispatch, don't touch the screen.
            if (command.cancelToken.isCancelled) {
                return GestureResult.Cancelled
            }

            val accepted = service.dispatchGesture(description, callback, null)
            if (!accepted) deferred.complete(false)

            val timedResult = withTimeoutOrNull(timeoutMs) { deferred.await() }

            return when (timedResult) {
                true -> GestureResult.Completed
                false -> GestureResult.Failed("gesture not accepted by system")
                null -> GestureResult.Failed("gesture timed out")
                else -> GestureResult.Failed("unexpected result")
            }
        } catch (e: CancellationException) {
            return GestureResult.Cancelled
        } catch (e: Exception) {
            return GestureResult.Failed(e.message ?: "dispatch exception")
        } finally {
            inFlight = null
            // Do NOT clear exclusiveOwner/wallTxnId here — wall-upgrade
            // transactions hold ownership continuously across multiple gestures.
            // Clearing is only done via cancelAll() (manual Stop) or
            // endWallTxn() when the transaction truly completes.
        }
    }

    private suspend fun dispatchGlobalBack(
        service: AccessibilityService,
        command: GestureCommand
    ): GestureResult {
        inFlight = command.transactionId
        try {
            // Double-check cancellation before the system call.
            if (command.cancelToken.isCancelled) return GestureResult.Cancelled

            kotlin.runCatching {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }.getOrDefault(false)

            return GestureResult.Completed
        } catch (e: CancellationException) {
            return GestureResult.Cancelled
        } catch (e: Exception) {
            return GestureResult.Failed(e.message ?: "global back exception")
        } finally {
            inFlight = null
            if (exclusiveOwner == command.sessionId) {
                exclusiveOwner = null
            }
        }
    }
    companion object {
        /**
         * The owner name the wall-list scanner uses for every swipe it sends.
         * The dispatch gate recognises exactly this owner plus the
         * `scan-scroll` reason prefix as the one gesture that stays legal
         * while "Allow tapping" is off.
         */
        const val SCAN_SCROLL_OWNER = "AutoWallsDevice"

        /** Reason prefix of the read-only list scroll. */
        const val SCAN_SCROLL_REASON_PREFIX = "scan-scroll"

        /**
         * Owners that belong to the wall pipeline. A command from one of them
         * is subject to the Allow-Tapping-OFF rule at the dispatch point.
         */
        val WALL_PIPELINE_OWNERS = setOf(
            SCAN_SCROLL_OWNER,
            "AutoWallsRuntime",
            "AutoWallsMachine"
        )

        /**
         * The one process-wide authority.
         *
         * Every automation surface — macro playback, image triggers, the
         * recovery watchdog and the wall scanner — receives THIS instance, so
         * there is exactly one mutex, one exclusive-ownership record and one
         * Allow-Tapping gate for the whole app. [ClahAccessibilityService]
         * binds the live accessibility service to it when it connects.
         */
        @Volatile
        private var sharedInstance: GestureAuthority? = null

        /** The shared authority, created on first use. */
        @JvmStatic
        fun shared(): GestureAuthority =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: GestureAuthority().also { sharedInstance = it }
            }

        /**
         * Replaces the shared instance. Used by tests that need a clean
         * authority; production never calls this.
         */
        @JvmStatic
        fun replaceShared(authority: GestureAuthority?) {
            synchronized(this) { sharedInstance = authority }
        }
    }
}

// ==================== internal helpers ====================

private fun GestureCommand.Kind.toGestureDescription(): GestureDescription {
    val builder = GestureDescription.Builder()
    val path = Path()
    when (this) {
        is GestureCommand.Kind.Tap -> {
            path.moveTo(box.centerX.toFloat(), box.centerY.toFloat())
            path.lineTo(box.centerX.toFloat(), box.centerY.toFloat())
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
        }
        is GestureCommand.Kind.Swipe -> {
            path.moveTo(fromX.toFloat(), fromY.toFloat())
            path.lineTo(toX.toFloat(), toY.toFloat())
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
        }
        else -> throw IllegalArgumentException("non-screen-touching kind: $this")
    }
    return builder.build()
}