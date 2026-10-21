package com.clahbot.data.models

data class RecordedGesture(
    val id: Int = 0,
    val timestampMs: Long = 0,
    val gestureType: String = "tap",
    val durationMs: Long = 80,
    val normalizedStartX: Float = 0f,
    val normalizedStartY: Float = 0f,
    val normalizedEndX: Float = 0f,
    val normalizedEndY: Float = 0f,
    val pressure: Float = 0f
)

data class Macro(
    val id: Long = 0,
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val deviceWidth: Int = 0,
    val deviceHeight: Int = 0,
    val orientation: String = "landscape",
    val gamePackage: String = "com.supercell.clashofclans",
    val gestures: List<RecordedGesture> = emptyList(),
    val repeatMode: String = "once",
    val speedMultiplier: Float = 1.0f,
    val randomOffset: String = "off"
)
