package com.clahbot.walls

import java.util.Locale

/**
 * One read-only scan of the wall list (Auto Walls 2.0 Phase 1 UI). It records
 * what the real pipeline found on the captured frame, what the user's selection
 * settings would choose, and whether tapping stayed locked. It carries no
 * gesture and is never an execution record.
 */
data class AutoWallsScanReport(
    val at: Long,
    val foregroundPackage: String? = null,
    val gameForeground: Boolean = false,
    val captured: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val lineCount: Int = 0,
    val screenState: String = "",
    val screenConfidence: Float = 0f,
    /** Every wall row discovered on the frame, in list order. */
    val candidates: List<WallCandidate> = emptyList(),
    /** Rows that pass Pay with + min confidence, ranked by the strategy. */
    val eligible: List<WallCandidate> = emptyList(),
    val selected: WallCandidate? = null,
    /** Exact reason nothing was selected, or null when [selected] is set. */
    val reason: String? = null,
    val strategy: WallSelectionStrategy = WallSelectionStrategy.CHEAPEST_FIRST,
    val currency: WallCurrency = WallCurrency.BOTH,
    val minConfidence: Float = 0.6f,
    val allowTapping: Boolean = false
) {
    val readableRows: Int
        get() = candidates.count { it.level > 0 && it.price > 0L && it.currency != "unknown" }
}

/**
 * Builds [AutoWallsScanReport]s from captured frames. Pure: it only replays
 * lines through [WallDiscovery] and [WallSelection], so a scan cannot tap,
 * scroll or start the machine — there is no device here at all.
 */
object AutoWallsScan {

    const val GAME_PACKAGE = "com.supercell.clashofclans"

    fun isGameForeground(pkg: String?): Boolean = pkg == GAME_PACKAGE

    fun analyze(
        frame: WallFrame,
        config: AutoWallsConfig,
        at: Long = System.currentTimeMillis()
    ): AutoWallsScanReport {
        val rows = frame.candidates()
        val ranked = WallSelection.rank(rows, config.selection)
        val selected = ranked.firstOrNull()
        val reason = when {
            rows.isEmpty() -> "no wall rows found on this frame"
            ranked.isEmpty() -> "${rows.size} row(s) found, none pass Pay with " +
                "${currencyLabel(config.selection.currency)} and min confidence " +
                confidenceLabel(config.selection.minConfidence)
            else -> null
        }
        return AutoWallsScanReport(
            at = at,
            foregroundPackage = GAME_PACKAGE,
            gameForeground = true,
            captured = true,
            frameWidth = frame.width,
            frameHeight = frame.height,
            lineCount = frame.lines.size,
            screenState = frame.observation.state.name,
            screenConfidence = frame.observation.confidence,
            candidates = rows,
            eligible = ranked,
            selected = selected,
            reason = reason,
            strategy = config.selection.strategy,
            currency = config.selection.currency,
            minConfidence = config.selection.minConfidence,
            allowTapping = config.allowTapping
        )
    }

    /** The game was not on screen, so nothing was captured or selected. */
    fun refused(
        foregroundPackage: String?,
        config: AutoWallsConfig,
        at: Long = System.currentTimeMillis()
    ): AutoWallsScanReport = AutoWallsScanReport(
        at = at,
        foregroundPackage = foregroundPackage,
        gameForeground = false,
        captured = false,
        reason = "Clash of Clans must be on screen (foreground: " +
            (foregroundPackage ?: "unreadable") + ")",
        strategy = config.selection.strategy,
        currency = config.selection.currency,
        minConfidence = config.selection.minConfidence,
        allowTapping = config.allowTapping
    )

    /** Capture or OCR failed; recorded as a report instead of a crash. */
    fun captureFailed(
        foregroundPackage: String?,
        config: AutoWallsConfig,
        at: Long = System.currentTimeMillis(),
        detail: String = "screen capture or OCR failed"
    ): AutoWallsScanReport = AutoWallsScanReport(
        at = at,
        foregroundPackage = foregroundPackage,
        gameForeground = isGameForeground(foregroundPackage),
        captured = false,
        reason = detail,
        strategy = config.selection.strategy,
        currency = config.selection.currency,
        minConfidence = config.selection.minConfidence,
        allowTapping = config.allowTapping
    )

    fun strategyLabel(strategy: WallSelectionStrategy): String = when (strategy) {
        WallSelectionStrategy.CHEAPEST_FIRST -> "Cheapest first"
        WallSelectionStrategy.HIGHEST_LEVEL_FIRST -> "Highest level"
        WallSelectionStrategy.LOWEST_LEVEL_FIRST -> "Lowest level"
        WallSelectionStrategy.FIRST_IN_LIST -> "List order"
        WallSelectionStrategy.CUSTOM -> "Custom"
    }

    fun criterionLabel(criterion: WallCriterion): String = when (criterion) {
        WallCriterion.CHEAPEST -> "Cheapest"
        WallCriterion.HIGHEST_LEVEL -> "Highest level"
        WallCriterion.LOWEST_LEVEL -> "Lowest level"
    }

    fun currencyLabel(currency: WallCurrency): String = when (currency) {
        WallCurrency.GOLD -> "Gold"
        WallCurrency.ELIXIR -> "Elixir"
        WallCurrency.BOTH -> "Both"
    }

    fun confidenceLabel(value: Float): String =
        String.format(Locale.US, "%.2f", value.coerceIn(0f, 1f))

    fun priceLabel(value: Long): String = String.format(Locale.US, "%,d", value)
}
