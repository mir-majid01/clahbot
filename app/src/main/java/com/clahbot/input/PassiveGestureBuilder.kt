package com.clahbot.input

import com.clahbot.data.models.GesturePoint

/**
 * Turns raw protocol-B frames into finished screen-space gestures.
 *
 * The first non-controller contact becomes the primary path. Every other
 * simultaneous contact is kept as an additional path, so a two-finger gesture
 * is one gesture rather than two unrelated taps. Contacts that start inside
 * the floating controller are ignored.
 */
class PassiveGestureBuilder(
    private val device: TouchscreenDevice,
    private val geometry: () -> ScreenGeometry?,
    private val controllerBounds: () -> ScreenRect?,
    private val onGesture: (BuiltGesture) -> Unit
) {

    private class PathState(
        val sequence: Int,
        val slot: Int,
        val pointerId: Int,
        val startOffsetMs: Long,
        val startAbsMs: Long,
        val points: MutableList<GesturePoint> = mutableListOf(),
        var lastX: Float = 0f,
        var lastY: Float = 0f
    )

    private val states = LinkedHashMap<Int, PathState>()
    private val finished = mutableListOf<PathState>()
    private var enabled = true
    private var active = false
    private var gestureStartMs = 0L
    private var nextSequence = 0

    @Synchronized
    fun onFrame(frame: RawTouchFrame) {
        if (!enabled) return
        val geometry = geometry() ?: run {
            resetLocked()
            return
        }
        val bounds = controllerBounds()

        if (!active) {
            if (frame.contacts.isNotEmpty()) startLocked(frame, geometry, bounds)
            return
        }

        val seen = HashSet<Int>()
        for (contact in frame.contacts) {
            val point = TouchTransform.toScreen(
                contact.rawX,
                contact.rawY,
                device,
                geometry.width,
                geometry.height,
                geometry.rotation
            )
            val state = states[contact.slot]
            if (state != null && state.pointerId == contact.trackingId) {
                if (point != null) appendLocked(state, point, frame.timestampMs)
                seen += contact.slot
            } else {
                if (state != null) {
                    states.remove(contact.slot)
                    finishLocked(state, frame.timestampMs)
                }
                if (point != null && !isOnController(bounds, point)) {
                    val newState = PathState(
                        sequence = nextSequence++,
                        slot = contact.slot,
                        pointerId = contact.trackingId,
                        startOffsetMs = (frame.timestampMs - gestureStartMs).coerceAtLeast(0L),
                        startAbsMs = frame.timestampMs
                    )
                    appendLocked(newState, point, frame.timestampMs)
                    states[contact.slot] = newState
                    seen += contact.slot
                }
            }
        }

        for (slot in states.keys.toList()) {
            if (slot in seen) continue
            val state = states.remove(slot) ?: continue
            finishLocked(state, frame.timestampMs)
        }

        if (states.isEmpty()) {
            active = false
            emitLocked()
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) resetLocked()
    }

    @Synchronized
    fun cancel() {
        resetLocked()
    }

    private fun startLocked(frame: RawTouchFrame, geometry: ScreenGeometry, bounds: ScreenRect?) {
        val captured = frame.contacts.mapNotNull { contact ->
            val point = TouchTransform.toScreen(
                contact.rawX,
                contact.rawY,
                device,
                geometry.width,
                geometry.height,
                geometry.rotation
            )
            if (point == null || isOnController(bounds, point)) null else contact to point
        }
        if (captured.isEmpty()) return

        active = true
        gestureStartMs = frame.timestampMs
        nextSequence = 0
        for ((contact, point) in captured) {
            val state = PathState(
                sequence = nextSequence++,
                slot = contact.slot,
                pointerId = contact.trackingId,
                startOffsetMs = 0L,
                startAbsMs = frame.timestampMs
            )
            appendLocked(state, point, frame.timestampMs)
            states[contact.slot] = state
        }
    }

    private fun appendLocked(state: PathState, point: ScreenPoint, timestampMs: Long) {
        val t = (timestampMs - state.startAbsMs).coerceAtLeast(0L)
        state.points += GesturePoint(point.x, point.y, t)
        state.lastX = point.x
        state.lastY = point.y
    }

    private fun finishLocked(state: PathState, endTimestampMs: Long) {
        val end = maxOf(endTimestampMs, state.startAbsMs)
        val duration = (end - state.startAbsMs).coerceAtLeast(1L)
        val last = state.points.lastOrNull()
        if (last == null ||
            last.t != duration ||
            last.x != state.lastX ||
            last.y != state.lastY
        ) {
            state.points += GesturePoint(state.lastX, state.lastY, duration)
        }
        finished += state
    }

    private fun emitLocked() {
        if (finished.isEmpty()) {
            resetLocked()
            return
        }
        val ordered = finished.sortedBy { it.sequence }
        val paths = ordered.map { state ->
            BuiltPath(
                pointerId = state.pointerId,
                startOffsetMs = state.startOffsetMs,
                points = state.points.toList()
            )
        }
        val duration = paths.maxOf { it.startOffsetMs + it.durationMs }
        val built = BuiltGesture(
            startUptimeMs = gestureStartMs,
            durationMs = duration,
            paths = paths
        )
        resetLocked()
        onGesture(built)
    }

    private fun resetLocked() {
        states.clear()
        finished.clear()
        active = false
        gestureStartMs = 0L
        nextSequence = 0
    }

    private fun isOnController(bounds: ScreenRect?, point: ScreenPoint): Boolean =
        bounds != null && bounds.contains(point.x, point.y)
}
