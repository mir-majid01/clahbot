package com.clahbot.walls

enum class WallPriority { GOLD_FIRST, ELIXIR_FIRST, FULLER_FIRST }

enum class WallResource { GOLD, ELIXIR }

enum class WallRunMode { TEST, LIVE }

/** The shared wall-line definition set up in the 10b overlay. */
data class WallLine(
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    /** Walls in this line; drives how progress maps onto the A->B span. */
    val totalWalls: Int,
    val wallsDone: Int
)

/** The camera-macro alignment landmark, in frame fractions. */
data class WallCamera(
    val landmarkLeft: Float,
    val landmarkTop: Float,
    val landmarkRight: Float,
    val landmarkBottom: Float,
    val patchFile: String
)

data class WallCycleLog(
    val at: Long,
    val mode: WallRunMode,
    val resource: WallResource,
    val walls: Int,
    val price: Long,
    val expectedPrice: Long,
    val balanceBefore: Long?,
    val balanceAfter: Long?,
    val status: String
)

data class WallSettings(
    val enabled: Boolean = false,
    val goldCost: Long = 0,
    val goldEnabled: Boolean = true,
    val elixirCost: Long = 0,
    val elixirEnabled: Boolean = true,
    val reserveGold: Long = 0,
    val reserveElixir: Long = 0,
    /** Run-when balance reaches this; 0 means only manual runs. */
    val thresholdGold: Long = 0,
    val thresholdElixir: Long = 0,
    val priority: WallPriority = WallPriority.GOLD_FIRST,
    val maxPerCycle: Int = 40,
    val wallLevel: Int = 0,
    /** Spend caps per resource; 0 disables the cap. */
    val capSession: Long = 0,
    val capDay: Long = 0,
    val line: WallLine? = null,
    val camera: WallCamera? = null,
    val cameraMacroId: Long = -1L,
    val history: List<WallCycleLog> = emptyList()
) {
    fun cost(resource: WallResource): Long = when (resource) {
        WallResource.GOLD -> goldCost
        WallResource.ELIXIR -> elixirCost
    }

    fun enabledFor(resource: WallResource): Boolean = when (resource) {
        WallResource.GOLD -> goldEnabled && goldCost > 0
        WallResource.ELIXIR -> elixirEnabled && elixirCost > 0
    }

    fun reserve(resource: WallResource): Long = when (resource) {
        WallResource.GOLD -> reserveGold
        WallResource.ELIXIR -> reserveElixir
    }

    fun threshold(resource: WallResource): Long = when (resource) {
        WallResource.GOLD -> thresholdGold
        WallResource.ELIXIR -> thresholdElixir
    }
}
