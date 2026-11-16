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
    val allowTapping: Boolean = false,
    /** Live gold/elixir counter readings, or null when the strip was unreadable. */
    val goldBalance: Long? = null,
    val elixirBalance: Long? = null,
    /** The balance above is carried over from an earlier frame and is not live. */
    val goldStale: Boolean = false,
    val elixirStale: Boolean = false,
    /** How many viewports the controlled top-to-bottom scan captured. */
    val viewports: Int = 0,
    /** True when the scan reached the end of the list (a viewport added nothing). */
    val bottomReached: Boolean = false,
    /** Why the scan loop stopped early, or null when it reached the bottom. */
    val scanNote: String? = null,
    val builderDetected: Boolean = false,
    val builderStatus: String = "",
    val builderFree: Int? = null,
    val builderTotal: Int? = null,
    val builderBusy: Int? = null,
    val builderGoblinOffer: Boolean = false,
    /** True when the scan actually answered the Goblin-offer question. */
    val builderGoblinKnown: Boolean = false,
    val builderConfidence: Float = 0f
) {
    val readableRows: Int
        get() = candidates.count { it.level > 0 && it.price > 0L && it.currency != "unknown" }

    /** Rows whose title, level, price and currency were all read. */
    val identifiedRows: Int
        get() = candidates.count { it.state == WallRowState.IDENTIFIED }

    /** Wall rows that were read but whose price or currency is missing. */
    val partialRows: Int
        get() = candidates.count { it.state == WallRowState.PARTIAL }

    /** Wall rows whose level or price stayed ambiguous. */
    val uncertainRows: Int
        get() = candidates.count { it.state == WallRowState.UNCERTAIN }

    /** Walls the gold balance could pay for across the discovered gold rows. */
    val affordableGold: Long?
        get() = AutoWallsScan.affordableWalls(
            candidates.filter { it.currency == "gold" }, goldBalance
        )

    /** Walls the elixir balance could pay for across the discovered elixir rows. */
    val affordableElixir: Long?
        get() = AutoWallsScan.affordableWalls(
            candidates.filter { it.currency == "elixir" }, elixirBalance
        )
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
    ): AutoWallsScanReport = analyzeScan(
        rows = frame.candidates(),
        frame = frame,
        config = config,
        counters = frame.counters(),
        builder = BuilderDetection.detect(
            frame.lines,
            frame.width,
            frame.height,
            frame.builderFaceAt
        ),
        viewports = 1,
        bottomReached = false,
        scanNote = null,
        at = at
    )

    /**
     * Builds the report for a controlled top-to-bottom scan: [rows] are the
     * merged, de-duplicated rows of every captured viewport, and [frame] is the
     * first captured frame (screen state and geometry). Pure — no device here.
     */
    fun analyzeScan(
        rows: List<WallCandidate>,
        frame: WallFrame,
        config: AutoWallsConfig,
        counters: WallCounters.Reading,
        builder: BuilderDetection.Reading,
        viewports: Int,
        bottomReached: Boolean,
        scanNote: String?,
        at: Long = System.currentTimeMillis()
    ): AutoWallsScanReport {
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
            allowTapping = config.allowTapping,
            goldBalance = counters.gold,
            elixirBalance = counters.elixir,
            goldStale = counters.goldStale,
            elixirStale = counters.elixirStale,
            viewports = viewports.coerceAtLeast(0),
            bottomReached = bottomReached,
            scanNote = scanNote,
            builderDetected = builder.status != BuilderDetection.Status.UNCERTAIN ||
                builder.builderApprenticeHelper || builder.bOBDetected ||
                builder.goblinBuilderOffer,
            builderStatus = builder.status.name,
            builderFree = builder.freeBuilders,
            builderTotal = builder.totalBuilders,
            builderBusy = builder.busyBuilders,
            builderGoblinOffer = builder.goblinBuilderOffer,
            builderGoblinKnown = builder.goblinOfferKnown,
            builderConfidence = builder.confidence
        )
    }

    /**
     * The wall list is not open (or its header could not be read), so the
     * scanner must not scroll blindly. The captured frame is still read for
     * the builder strip and the balances: those are independent of the wall
     * rows, and on the device a plainly visible `0/6 | 1/2 | 0/1` strip used
     * to be reported as "not detected" only because the header read failed.
     */
    fun listNotDetected(
        foregroundPackage: String?,
        config: AutoWallsConfig,
        at: Long = System.currentTimeMillis(),
        detail: String = "Buildings & Heroes list not detected - open the wall list, " +
            "then tap Scan screen again",
        frame: WallFrame? = null
    ): AutoWallsScanReport {
        val builder = frame?.let {
            BuilderDetection.detect(it.lines, it.width, it.height, it.builderFaceAt)
        }
        val counters = frame?.counters()
        return AutoWallsScanReport(
            at = at,
            foregroundPackage = foregroundPackage,
            gameForeground = isGameForeground(foregroundPackage),
            captured = true,
            frameWidth = frame?.width ?: 0,
            frameHeight = frame?.height ?: 0,
            lineCount = frame?.lines?.size ?: 0,
            screenState = frame?.observation?.state?.name ?: "",
            screenConfidence = frame?.observation?.confidence ?: 0f,
            reason = detail,
            strategy = config.selection.strategy,
            currency = config.selection.currency,
            minConfidence = config.selection.minConfidence,
            allowTapping = config.allowTapping,
            goldBalance = counters?.gold,
            elixirBalance = counters?.elixir,
            goldStale = counters?.goldStale ?: false,
            elixirStale = counters?.elixirStale ?: false,
            builderDetected = builder != null && (
                builder.status != BuilderDetection.Status.UNCERTAIN ||
                    builder.builderApprenticeHelper || builder.bOBDetected ||
                    builder.goblinBuilderOffer
                ),
            builderStatus = builder?.status?.name ?: "",
            builderFree = builder?.freeBuilders,
            builderTotal = builder?.totalBuilders,
            builderBusy = builder?.busyBuilders,
            builderGoblinOffer = builder?.goblinBuilderOffer ?: false,
            builderGoblinKnown = builder?.goblinOfferKnown ?: false,
            builderConfidence = builder?.confidence ?: 0f
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

    /**
     * How many wall upgrades [balance] could pay for across [rows]: the cheapest
     * rows first, at most each row's read wall count (a row without one counts
     * as a single wall), stopping when nothing more is affordable. Rows with an
     * unreadable price are skipped. A null balance (unreadable counter) says
     * nothing, so the result is null too.
     */
    fun affordableWalls(rows: List<WallCandidate>, balance: Long?): Long? {
        balance ?: return null
        var remaining = balance
        var total = 0L
        for (candidate in rows.filter { it.price > 0L }.sortedBy { it.price }) {
            if (remaining < candidate.price) break
            val wallsInRow = (candidate.count ?: 1).coerceAtLeast(1).toLong()
            val affordable = minOf(wallsInRow, remaining / candidate.price)
            total += affordable
            remaining -= affordable * candidate.price
        }
        return total
    }
}
