package com.clahbot.input

import com.clahbot.data.models.GesturePoint

data class TouchscreenDevice(
    val path: String,
    val name: String,
    val maxX: Int,
    val maxY: Int,
    val maxSlots: Int = 0,
    val direct: Boolean = false
)

data class ScreenGeometry(
    val width: Int,
    val height: Int,
    val rotation: Int
)

data class ScreenPoint(val x: Float, val y: Float)

data class ScreenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

data class RawTouchContact(
    val slot: Int,
    val trackingId: Int,
    val rawX: Int,
    val rawY: Int
)

data class RawTouchFrame(
    val timestampMs: Long,
    val contacts: List<RawTouchContact>
)

data class BuiltPath(
    val pointerId: Int,
    val startOffsetMs: Long,
    val points: List<GesturePoint>
) {
    val durationMs: Long get() = points.lastOrNull()?.t ?: 0L
}

data class BuiltGesture(
    val startUptimeMs: Long,
    val durationMs: Long,
    val paths: List<BuiltPath>
) {
    val primary: BuiltPath get() = paths.first()
}
