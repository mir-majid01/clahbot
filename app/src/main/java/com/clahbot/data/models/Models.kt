package com.clahbot.data.models

import android.graphics.Point

enum class GameScreen {
    UNKNOWN,
    HOME_VILLAGE,
    ATTACK_SEARCH,
    ATTACK_DEPLOY,
    SHOP,
    BUILDER_BASE,
    WARNING,
    LOADING
}

enum class AutomationState {
    IDLE,
    COLLECTING,
    SEARCHING,
    ATTACKING,
    UPGRADING,
    PAUSED,
    STOPPED
}

enum class AutomationMode {
    SEARCH_ONLY,
    COLLECT_ONLY,
    FULL_FARM,
    BUILDER_BASE
}

data class TargetPoint(
    val x: Int,
    val y: Int,
    val label: String = "",
    val confidence: Float = 1f
) {
    fun toPoint(): Point = Point(x, y)
}

data class VisionSnapshot(
    val collectors: List<TargetPoint> = emptyList(),
    val attackButton: TargetPoint? = null,
    val searchButton: TargetPoint? = null,
    val nextButton: TargetPoint? = null,
    val lootGold: String? = null,
    val lootElixir: String? = null,
    val lootDark: String? = null,
    val gameScreen: GameScreen = GameScreen.UNKNOWN,
    val hasRedLine: Boolean = false,
    val warningText: String? = null,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class SessionStats(
    val resourcesCollected: Long = 0,
    val attacksLaunched: Int = 0,
    val searchesPerformed: Int = 0,
    val sessionStartMs: Long = System.currentTimeMillis(),
    val lootPerHour: Long = 0
)

data class BotConfig(
    val mode: AutomationMode = AutomationMode.SEARCH_ONLY,
    val workMinutes: Int = 45,
    val restMinutes: Int = 15,
    val minGold: Long = 100_000,
    val minElixir: Long = 100_000,
    val minDark: Long = 0,
    val tapJitterPx: Int = 10,
    val adaptiveFpsIdle: Int = 2,
    val adaptiveFpsActive: Int = 5,
    val sessionLimitMinutes: Int = 120,
    val autoPauseOnWarning: Boolean = true
)
