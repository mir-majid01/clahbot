package com.clahbot.data.models

/**
 * A single sample on a recorded gesture path. [x] and [y] are normalized to
 * 0..1 of the capture surface, [t] is milliseconds since the gesture started.
 */
data class GesturePoint(
    val x: Float = 0f,
    val y: Float = 0f,
    val t: Long = 0L
)

data class RecordedGesture(
    val id: Int = 0,
    val timestampMs: Long = 0,
    val gestureType: String = "tap",
    val durationMs: Long = 80,
    val normalizedStartX: Float = 0f,
    val normalizedStartY: Float = 0f,
    val normalizedEndX: Float = 0f,
    val normalizedEndY: Float = 0f,
    val pressure: Float = 0f,
    /** Full movement path. Empty on macros recorded by schema version 1. */
    val points: List<GesturePoint> = emptyList()
)

data class Macro(
    val id: Long = 0,
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val deviceWidth: Int = 0,
    val deviceHeight: Int = 0,
    val orientation: String = "portrait",
    val gamePackage: String = "com.supercell.clashofclans",
    val gestures: List<RecordedGesture> = emptyList(),
    /** "once", "infinite", or a positive integer as a string. */
    val repeatMode: String = "once",
    val speedMultiplier: Float = 1.0f,
    val randomOffset: String = "off",
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    val loops: Boolean get() = repeatMode == REPEAT_INFINITE

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val REPEAT_ONCE = "once"
        const val REPEAT_INFINITE = "infinite"
    }
}
