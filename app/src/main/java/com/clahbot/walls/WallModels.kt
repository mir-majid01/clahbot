package com.clahbot.walls

import com.google.gson.annotations.SerializedName

/** Which resource the wall upgrade should come from. */
enum class WallResourceMode {
    GOLD,
    ELIXIR,
    BOTH;

    val label: String
        get() = when (this) {
            GOLD -> "Gold"
            ELIXIR -> "Elixir"
            BOTH -> "Both"
        }
}

/** Which resource wins when both can afford the wall in BOTH mode. */
enum class WallPriority {
    GOLD_FIRST,
    ELIXIR_FIRST;

    val label: String
        get() = if (this == GOLD_FIRST) "Gold first" else "Elixir first"
}

/** What the automation decided to do on one home-village visit. */
enum class WallTrigger {
    NONE,
    GOLD,
    ELIXIR;

    val isRun: Boolean get() = this != NONE
}

/**
 * One recorded wall attempt. The balances before and after are what the app
 * actually measured, so the spent amounts are derived, never guessed.
 */
data class WallRunLog(
    @SerializedName("at") val at: Long,
    @SerializedName("macroName") val macroName: String,
    @SerializedName("resource") val resource: WallTrigger,
    @SerializedName("goldBefore") val goldBefore: Long?,
    @SerializedName("goldAfter") val goldAfter: Long?,
    @SerializedName("elixirBefore") val elixirBefore: Long?,
    @SerializedName("elixirAfter") val elixirAfter: Long?,
    @SerializedName("status") val status: String,
    @SerializedName("detail") val detail: String = ""
) {
    val spentGold: Long?
        get() = if (goldBefore != null && goldAfter != null && goldAfter <= goldBefore) {
            goldBefore - goldAfter
        } else {
            null
        }

    val spentElixir: Long?
        get() = if (elixirBefore != null && elixirAfter != null && elixirAfter <= elixirBefore) {
            elixirBefore - elixirAfter
        } else {
            null
        }
}

/**
 * The whole wall configuration. One cost applies to gold and elixir because
 * walls cost the same in both resources, and the reserve is optional.
 */
data class WallSettings(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("wallMacroId") val wallMacroId: Long = -1L,
    @SerializedName("resourceMode") val resourceMode: WallResourceMode = WallResourceMode.BOTH,
    @SerializedName("priority") val priority: WallPriority = WallPriority.GOLD_FIRST,
    @SerializedName("costPerWall") val costPerWall: Long = 0L,
    @SerializedName("minReserve") val minReserve: Long = 0L,
    /** Upgrade again on the same home visit when another upgrade is verified. */
    @SerializedName("allowRepeatUpgrades") val allowRepeatUpgrades: Boolean = false,
    /**
     * The wall macro the user last played to the end with Run now. A freshly
     * recorded macro is never run automatically until it appears here.
     */
    @SerializedName("testedWallMacroId") val testedWallMacroId: Long = -1L,
    @SerializedName("history") val history: List<WallRunLog> = emptyList()
) {
    fun cost(): Long = costPerWall.coerceAtLeast(0L)

    fun reserve(): Long = minReserve.coerceAtLeast(0L)

    fun isTested(macroId: Long): Boolean = testedWallMacroId == macroId

    /**
     * Drops a selection whose wall macro no longer exists, so a deleted macro
     * never stays "selected" against a missing id. The switch and the rest of
     * the configuration are left alone: losing a macro is not a reason to turn
     * Auto Walls off or forget the cost the user typed.
     */
    fun reconciled(availableWallMacroIds: Set<Long>): WallSettings {
        val macroId =
            if (wallMacroId > 0L && wallMacroId !in availableWallMacroIds) -1L else wallMacroId
        val testedId =
            if (testedWallMacroId > 0L && testedWallMacroId !in availableWallMacroIds) -1L
            else testedWallMacroId
        return if (macroId == wallMacroId && testedId == testedWallMacroId) this
        else copy(wallMacroId = macroId, testedWallMacroId = testedId)
    }

    /** One status line shared by the controller tab and the dashboard card. */
    fun summary(macroName: String?): String = when {
        wallMacroId <= 0L -> "Record a wall upgrade macro to start"
        macroName.isNullOrBlank() -> "The wall macro you picked is gone · select it again"
        costPerWall <= 0L -> "Enter the cost per wall"
        !enabled -> "Off · turn on to upgrade after each attack"
        testedWallMacroId != wallMacroId -> "Run it once with Run now before automating"
        else -> buildString {
            append("Ready · upgrades with \"")
            append(macroName ?: "the wall macro")
            append("\" after an attack")
            if (minReserve > 0L) append(" · keeps ${formatWallAmount(minReserve)} in reserve")
        }
    }
}

/** 1.4M / 250K style, used by both wall surfaces. */
fun formatWallAmount(value: Long): String = when {
    value <= 0L -> "0"
    value >= 1_000_000L -> {
        val millions = value / 1_000_000.0
        if (millions == millions.toLong().toDouble()) {
            "${millions.toLong()}M"
        } else {
            String.format(java.util.Locale.US, "%.1fM", millions)
        }
    }
    value >= 1_000L -> "${value / 1_000}K"
    else -> value.toString()
}
