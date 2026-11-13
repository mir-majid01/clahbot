package com.clahbot.walls

import android.content.Context
import com.clahbot.data.AppEventLog
import com.google.gson.Gson
import java.io.File

/** What a restart needs to know about a cut-off run, for diagnostics only. */
data class InterruptedRun(
    val state: AutoWallsState,
    val candidate: WallCandidate?,
    val updatedAt: Long,
    /** True when the confirm tap may already have been sent. */
    val tapMayHaveOccurred: Boolean
)

data class AutoWallsRecovery(
    val config: AutoWallsConfig,
    val interrupted: InterruptedRun?
)

/** One persisted Auto Walls 2.0 history record (PRD §9). */
data class WallHistoryEntry(
    val at: Long,
    val status: WallEventStatus,
    val level: Int,
    val price: Long,
    val currency: String,
    val detail: String = ""
)

/**
 * Persists Auto Walls 2.0 configuration, the live run snapshot, the trigger
 * state, the last scan and the upgrade history (PRD §11). All five files use
 * the same atomic-write + corrupt-quarantine pattern as the other
 * repositories, so a crash mid-write never destroys the previous copy.
 *
 * A run snapshot is written on every transition. [recover] never resumes an
 * interrupted run: it records the interruption in history, clears the run file
 * and leaves the machine to start idle, so the next attempt always re-scans and
 * re-validates the confirmation from scratch.
 */
class AutoWallsRepository internal constructor(
    private val configFile: File,
    private val runFile: File,
    private val historyFile: File,
    private val triggerFile: File = File(configFile.parentFile, TRIGGER_FILE),
    private val scanFile: File = File(configFile.parentFile, SCAN_FILE)
) {

    constructor(context: Context) : this(
        File(context.filesDir, CONFIG_FILE),
        File(context.filesDir, RUN_FILE),
        File(context.filesDir, HISTORY_FILE),
        File(context.filesDir, TRIGGER_FILE)
    )

    private val gson = Gson()

    // ---- configuration -----------------------------------------------------

    @Synchronized
    fun loadConfig(): AutoWallsConfig {
        val stored = readChecked(configFile, StoredConfig::class.java) ?: StoredConfig()
        return stored.toConfig()
    }

    @Synchronized
    fun saveConfig(config: AutoWallsConfig) {
        write(configFile, gson.toJson(StoredConfig.from(config)))
    }

    // ---- combined trigger settings + counters ------------------------------

    @Synchronized
    fun loadTrigger(): WallTriggerState {
        val stored = readChecked(triggerFile, StoredTrigger::class.java) ?: return WallTriggerState()
        return WallTriggerState(
            config = stored.config?.toConfig() ?: WallTriggerConfig(),
            counters = stored.counters?.toCounters() ?: WallTriggerCounters()
        )
    }

    @Synchronized
    fun saveTrigger(state: WallTriggerState) {
        write(triggerFile, gson.toJson(StoredTrigger.from(state)))
    }

    // ---- live run snapshot -------------------------------------------------

    @Synchronized
    fun loadRun(): AutoWallsSnapshot? {
        val stored = readChecked(runFile, StoredRun::class.java) ?: return null
        val state = stored.state?.let { runCatching { AutoWallsState.valueOf(it) }.getOrNull() }
        if (state == null) {
            quarantine(runFile)
            return null
        }
        val id = stored.candidateId
        val candidate = if (id == null) {
            null
        } else {
            val level = stored.level
            val price = stored.price
            val currency = stored.currency
            if (level == null || price == null || currency == null) {
                quarantine(runFile)
                return null
            }
            WallCandidate(
                id = id,
                level = level,
                price = price,
                currency = currency,
                listIndex = stored.listIndex ?: 0,
                confidence = stored.confidence ?: 0f,
                count = stored.count
            )
        }
        return AutoWallsSnapshot(state, candidate, stored.scrolls)
    }

    @Synchronized
    fun saveRun(snapshot: AutoWallsSnapshot, at: Long = System.currentTimeMillis()) {
        val c = snapshot.candidate
        write(
            runFile,
            gson.toJson(
                StoredRun(
                    state = snapshot.state.name,
                    candidateId = c?.id,
                    level = c?.level,
                    price = c?.price,
                    currency = c?.currency,
                    listIndex = c?.listIndex,
                    confidence = c?.confidence,
                    count = c?.count,
                    scrolls = snapshot.scrolls,
                    updatedAt = at,
                    schemaVersion = SCHEMA_VERSION
                )
            )
        )
    }

    @Synchronized
    fun clearRun() {
        runFile.delete()
    }

    // ---- history -----------------------------------------------------------

    /** Newest first, bounded to [MAX_HISTORY] entries. */
    @Synchronized
    fun history(): List<WallHistoryEntry> {
        val stored = readChecked(historyFile, StoredHistory::class.java) ?: return emptyList()
        return stored.entries.orEmpty().mapNotNull { it.toEntry() }.asReversed()
    }

    @Synchronized
    fun record(event: WallEvent, at: Long = System.currentTimeMillis()) {
        appendHistory(
            WallHistoryEntry(at, event.status, event.level, event.price, event.currency, event.detail)
        )
    }

    @Synchronized
    fun clearHistory() {
        historyFile.delete()
    }

    // ---- last scan (read-only diagnostics, Phase 1 UI) ---------------------

    /**
     * The last read-only scan report. A stored report is diagnostics only: it
     * can never turn tapping on, so [AutoWallsScanReport.allowTapping] is always
     * false after a reload.
     */
    @Synchronized
    fun loadScan(): AutoWallsScanReport? {
        val stored = readChecked(scanFile, StoredScan::class.java) ?: return null
        return stored.toReport()
    }

    @Synchronized
    fun saveScan(report: AutoWallsScanReport) {
        write(scanFile, gson.toJson(StoredScan.from(report)))
    }

    // ---- restart recovery --------------------------------------------------

    /**
     * Reads the persisted config and resolves a run that was cut off. Pending
     * runs are recorded once in history and cleared; nothing is auto-repeated.
     */
    @Synchronized
    fun recover(at: Long = System.currentTimeMillis()): AutoWallsRecovery {
        val config = loadConfig()
        val run = loadRun() ?: return AutoWallsRecovery(config, null)
        if (run.state !in PENDING_STATES) {
            clearRun()
            return AutoWallsRecovery(config, null)
        }
        val tapMayHaveOccurred =
            run.state == AutoWallsState.EXECUTING_UPGRADE || run.state == AutoWallsState.VERIFYING_RESULT
        val updatedAt = readRunTime()
        val c = run.candidate
        appendHistory(
            WallHistoryEntry(
                at = at,
                status = WallEventStatus.FAILED,
                level = c?.level ?: 0,
                price = c?.price ?: 0L,
                currency = c?.currency ?: "unknown",
                detail = if (tapMayHaveOccurred) {
                    "interrupted during ${run.state}; outcome unknown after the confirm tap, not retried"
                } else {
                    "interrupted during ${run.state}; fresh scan required"
                }
            )
        )
        clearRun()
        return AutoWallsRecovery(config, InterruptedRun(run.state, c, updatedAt, tapMayHaveOccurred))
    }

    // ---- file helpers ------------------------------------------------------

    private fun appendHistory(entry: WallHistoryEntry) {
        val current = readChecked(historyFile, StoredHistory::class.java)?.entries.orEmpty()
        val next = (current + StoredHistoryEntry.from(entry)).takeLast(MAX_HISTORY)
        write(historyFile, gson.toJson(StoredHistory(next, SCHEMA_VERSION)))
    }

    private fun readRunTime(): Long {
        val stored = readChecked(runFile, StoredRun::class.java) ?: return 0L
        return stored.updatedAt
    }

    private fun <T> read(file: File, type: Class<T>): T? {
        if (!file.exists()) return null
        val parsed = runCatching { gson.fromJson(file.readText(), type) }.getOrElse {
            quarantine(file)
            return null
        }
        if (parsed == null) {
            quarantine(file)
            return null
        }
        return parsed
    }

    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private fun quarantine(file: File) {
        try {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
        } catch (_: Exception) {
            // Unreadable content is treated as absent; nothing else to preserve.
        }
    }

    /**
     * Root marker for the persisted walls files: every stored root carries the
     * schema version it was written with, so an older build can recognize a
     * file it cannot fully interpret instead of silently dropping fields.
     */
    private interface StoredFile {
        val schemaVersion: Int
    }

    /**
     * Reads a stored file and quarantines anything written by a newer build:
     * loading it here would drop its unknown fields on the next save, so the
     * bytes are preserved as `.corrupt-…` and the state is treated as absent.
     * A missing or older version (0 = the pre-versioning files) is accepted:
     * missing keys fall back to their stored defaults, and the next save
     * rewrites the file at the current version.
     */
    private fun <T : StoredFile> readChecked(file: File, type: Class<T>): T? {
        val parsed = read(file, type) ?: return null
        if (parsed.schemaVersion > SCHEMA_VERSION) {
            AppEventLog.log(
                "auto walls ${file.name}: schema ${parsed.schemaVersion} > $SCHEMA_VERSION, quarantined"
            )
            quarantine(file)
            return null
        }
        if (parsed.schemaVersion < SCHEMA_VERSION) {
            AppEventLog.log("auto walls ${file.name}: legacy schema ${parsed.schemaVersion}, upgraded on next save")
        }
        return parsed
    }

    // ---- stored (Gson-safe) shapes ----------------------------------------

    private data class StoredConfig(
        val enabled: Boolean = false,
        val strategy: String? = null,
        val customOrder: List<String>? = null,
        val currency: String? = null,
        val minConfidence: Float? = null,
        val maxScrolls: Int? = null,
        override val schemaVersion: Int = 0
    ) : StoredFile {
        fun toConfig(): AutoWallsConfig = AutoWallsConfig(
            enabled = enabled,
            selection = WallSelectionConfig(
                strategy = strategy.enumOr(WallSelectionStrategy.CHEAPEST_FIRST),
                customOrder = customOrder.orEmpty()
                    .mapNotNull { name -> name.enumOrNull<WallCriterion>() },
                currency = currency.enumOr(WallCurrency.BOTH),
                minConfidence = (minConfidence ?: 0.6f).coerceIn(0f, 1f)
            ),
            // Taps never resume from disk; a build must enable them itself.
            allowTapping = false,
            // Corrupt values must not turn into endless scrolling.
            maxScrolls = (maxScrolls ?: 4).coerceIn(0, 20)
        )

        companion object {
            fun from(config: AutoWallsConfig) = StoredConfig(
                enabled = config.enabled,
                strategy = config.selection.strategy.name,
                customOrder = config.selection.customOrder.map { it.name },
                currency = config.selection.currency.name,
                minConfidence = config.selection.minConfidence,
                maxScrolls = config.maxScrolls,
                schemaVersion = SCHEMA_VERSION
            )
        }
    }

    private data class StoredRun(
        val state: String? = null,
        val candidateId: String? = null,
        val level: Int? = null,
        val price: Long? = null,
        val currency: String? = null,
        val listIndex: Int? = null,
        val confidence: Float? = null,
        val count: Int? = null,
        val scrolls: Int = 0,
        val updatedAt: Long = 0L,
        override val schemaVersion: Int = 0
    ) : StoredFile

    private data class StoredTrigger(
        val config: StoredTriggerConfig? = null,
        val counters: StoredTriggerCounters? = null,
        override val schemaVersion: Int = 0
    ) : StoredFile {
        companion object {
            fun from(state: WallTriggerState) = StoredTrigger(
                config = StoredTriggerConfig(
                    attacksBetweenUpgrades = state.config.attacksBetweenUpgrades,
                    goldThreshold = state.config.goldThreshold,
                    elixirThreshold = state.config.elixirThreshold,
                    goldReserve = state.config.goldReserve,
                    elixirReserve = state.config.elixirReserve,
                    cooldownMillis = state.config.cooldownMillis,
                    maxUpgradesPerSession = state.config.maxUpgradesPerSession,
                    maxUpgradesPerRun = state.config.maxUpgradesPerRun
                ),
                counters = StoredTriggerCounters(
                    attacksSinceVerified = state.counters.attacksSinceVerified,
                    upgradesThisRun = state.counters.upgradesThisRun,
                    lastVerifiedAt = state.counters.lastVerifiedAt
                ),
                schemaVersion = SCHEMA_VERSION
            )
        }
    }

    private data class StoredTriggerConfig(
        val attacksBetweenUpgrades: Int? = null,
        val goldThreshold: Long? = null,
        val elixirThreshold: Long? = null,
        val goldReserve: Long? = null,
        val elixirReserve: Long? = null,
        val cooldownMillis: Long? = null,
        val maxUpgradesPerSession: Int? = null,
        val maxUpgradesPerRun: Int? = null
    ) {
        fun toConfig() = WallTriggerConfig(
            attacksBetweenUpgrades = (attacksBetweenUpgrades ?: 10).coerceAtLeast(0),
            goldThreshold = (goldThreshold ?: 5_000_000L).coerceAtLeast(0L),
            elixirThreshold = (elixirThreshold ?: 5_000_000L).coerceAtLeast(0L),
            goldReserve = (goldReserve ?: 1_000_000L).coerceAtLeast(0L),
            elixirReserve = (elixirReserve ?: 1_000_000L).coerceAtLeast(0L),
            cooldownMillis = (cooldownMillis ?: 0L).coerceAtLeast(0L),
            maxUpgradesPerSession = maxUpgradesPerSession?.takeIf { it >= 0 },
            maxUpgradesPerRun = maxUpgradesPerRun?.takeIf { it >= 0 }
        )
    }

    private data class StoredTriggerCounters(
        val attacksSinceVerified: Int? = null,
        val upgradesThisRun: Int? = null,
        val lastVerifiedAt: Long? = null
    ) {
        fun toCounters() = WallTriggerCounters(
            attacksSinceVerified = (attacksSinceVerified ?: 0).coerceAtLeast(0),
            upgradesThisRun = (upgradesThisRun ?: 0).coerceAtLeast(0),
            lastVerifiedAt = lastVerifiedAt?.takeIf { it > 0L }
        )
    }

    private data class StoredScan(
        val at: Long = 0L,
        val foregroundPackage: String? = null,
        val gameForeground: Boolean = false,
        val captured: Boolean = false,
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
        val lineCount: Int = 0,
        val screenState: String? = null,
        val screenConfidence: Float? = null,
        val candidates: List<StoredScanCandidate>? = null,
        val eligible: List<StoredScanCandidate>? = null,
        val selected: StoredScanCandidate? = null,
        val reason: String? = null,
        val strategy: String? = null,
        val currency: String? = null,
        val minConfidence: Float? = null,
        val allowTapping: Boolean = false,
        val goldBalance: Long? = null,
        val elixirBalance: Long? = null,
        override val schemaVersion: Int = 0
    ) : StoredFile {
        fun toReport(): AutoWallsScanReport = AutoWallsScanReport(
            at = at,
            foregroundPackage = foregroundPackage,
            gameForeground = gameForeground,
            captured = captured,
            frameWidth = frameWidth.coerceAtLeast(0),
            frameHeight = frameHeight.coerceAtLeast(0),
            lineCount = lineCount.coerceAtLeast(0),
            screenState = screenState.orEmpty(),
            screenConfidence = (screenConfidence ?: 0f).coerceIn(0f, 1f),
            candidates = candidates.orEmpty().take(MAX_SCAN_ROWS)
                .mapNotNull { it.toCandidate() },
            eligible = eligible.orEmpty().take(MAX_SCAN_ROWS)
                .mapNotNull { it.toCandidate() },
            selected = selected?.toCandidate(),
            reason = reason,
            strategy = strategy.enumOr(WallSelectionStrategy.CHEAPEST_FIRST),
            currency = currency.enumOr(WallCurrency.BOTH),
            minConfidence = (minConfidence ?: 0.6f).coerceIn(0f, 1f),
            // A stored report must never claim it unlocked tapping.
            allowTapping = false,
            // A negative stored balance is garbage, never a real reading.
            goldBalance = goldBalance?.takeIf { it >= 0L },
            elixirBalance = elixirBalance?.takeIf { it >= 0L }
        )

        companion object {
            fun from(report: AutoWallsScanReport) = StoredScan(
                at = report.at,
                foregroundPackage = report.foregroundPackage,
                gameForeground = report.gameForeground,
                captured = report.captured,
                frameWidth = report.frameWidth,
                frameHeight = report.frameHeight,
                lineCount = report.lineCount,
                screenState = report.screenState,
                screenConfidence = report.screenConfidence,
                candidates = report.candidates.map { StoredScanCandidate.from(it) },
                eligible = report.eligible.map { StoredScanCandidate.from(it) },
                selected = report.selected?.let { StoredScanCandidate.from(it) },
                reason = report.reason,
                strategy = report.strategy.name,
                currency = report.currency.name,
                minConfidence = report.minConfidence,
                allowTapping = report.allowTapping,
                goldBalance = report.goldBalance,
                elixirBalance = report.elixirBalance,
                schemaVersion = SCHEMA_VERSION
            )
        }
    }

    private data class StoredScanCandidate(
        val id: String? = null,
        val level: Int? = null,
        val price: Long? = null,
        val currency: String? = null,
        val listIndex: Int? = null,
        val confidence: Float? = null,
        val count: Int? = null
    ) {
        /** Unreadable identity/level/price/currency rows are dropped, never guessed. */
        fun toCandidate(): WallCandidate? {
            val id = id ?: return null
            val level = level ?: return null
            val price = price ?: return null
            val currency = currency ?: return null
            return WallCandidate(
                id = id,
                level = level.coerceAtLeast(0),
                price = price.coerceAtLeast(0L),
                currency = currency,
                listIndex = (listIndex ?: 0).coerceAtLeast(0),
                confidence = (confidence ?: 0f).coerceIn(0f, 1f),
                count = count?.takeIf { it >= 0 }
            )
        }

        companion object {
            fun from(candidate: WallCandidate) = StoredScanCandidate(
                id = candidate.id,
                level = candidate.level,
                price = candidate.price,
                currency = candidate.currency,
                listIndex = candidate.listIndex,
                confidence = candidate.confidence,
                count = candidate.count
            )
        }
    }

    private data class StoredHistory(
        /** Nullable: Gson bypasses the constructor, so a missing key arrives as null. */
        val entries: List<StoredHistoryEntry>? = null,
        override val schemaVersion: Int = 0
    ) : StoredFile

    private data class StoredHistoryEntry(
        val at: Long = 0L,
        val status: String? = null,
        val level: Int = 0,
        val price: Long = 0L,
        val currency: String = "",
        val detail: String = ""
    ) {
        fun toEntry(): WallHistoryEntry? {
            val parsed = status?.let { runCatching { WallEventStatus.valueOf(it) }.getOrNull() }
                ?: return null
            return WallHistoryEntry(at, parsed, level, price, currency, detail)
        }

        companion object {
            fun from(entry: WallHistoryEntry) = StoredHistoryEntry(
                at = entry.at,
                status = entry.status.name,
                level = entry.level,
                price = entry.price,
                currency = entry.currency,
                detail = entry.detail
            )
        }
    }

    companion object {
        private const val CONFIG_FILE = "auto_walls_config.json"
        private const val RUN_FILE = "auto_walls_run.json"
        private const val HISTORY_FILE = "auto_walls_history.json"
        private const val TRIGGER_FILE = "auto_walls_trigger.json"
        private const val SCAN_FILE = "auto_walls_scan.json"
        private const val SCHEMA_VERSION = 1

        private const val MAX_HISTORY = 200
        private const val MAX_SCAN_ROWS = 200

        private val PENDING_STATES = setOf(
            AutoWallsState.EVALUATING_CONDITIONS,
            AutoWallsState.OPENING_UPGRADE_LIST,
            AutoWallsState.SCANNING_WALLS,
            AutoWallsState.RANKING_CANDIDATES,
            AutoWallsState.SELECTING_WALL,
            AutoWallsState.VALIDATING_CONFIRMATION,
            AutoWallsState.EXECUTING_UPGRADE,
            AutoWallsState.VERIFYING_RESULT
        )

        @Volatile
        private var instance: AutoWallsRepository? = null

        fun get(context: Context): AutoWallsRepository =
            instance ?: synchronized(this) {
                instance ?: AutoWallsRepository(context.applicationContext).also { instance = it }
            }
    }
}

private inline fun <reified T : Enum<T>> String?.enumOr(fallback: T): T =
    this?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() } ?: fallback

private inline fun <reified T : Enum<T>> String?.enumOrNull(): T? =
    this?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() }

/**
 * Wires a machine to the repository: every transition updates the run file and
 * every milestone is appended to history. [start] performs restart recovery
 * first, so a crash mid-upgrade is recorded instead of silently retried.
 */
class AutoWallsSession(
    private val repository: AutoWallsRepository,
    private val now: () -> Long = System::currentTimeMillis
) : AutoWallsMachine.Observer {

    var machine: AutoWallsMachine? = null
        private set

    var recovery: AutoWallsRecovery? = null
        private set

    fun start(allowTapping: Boolean = false): AutoWallsMachine {
        val recovered = repository.recover(now())
        recovery = recovered
        // The tap gate is supplied by the build/runner, never restored from disk.
        val config = recovered.config.copy(allowTapping = allowTapping)
        // A run boundary resets the per-run upgrade counter (PRD §5.4). Without
        // this the persisted count would survive into the next run and
        // maxUpgradesPerRun would silently behave like a per-install limit.
        // Attacks-since-verified and cooldown counters are intentionally kept:
        // only a verified upgrade resets those.
        val trigger = repository.loadTrigger()
        if (trigger.counters.upgradesThisRun != 0) {
            repository.saveTrigger(trigger.copy(counters = trigger.counters.onRunStarted()))
            AppEventLog.log(
                "auto walls run start: upgradesThisRun reset (was ${trigger.counters.upgradesThisRun})"
            )
        }
        return AutoWallsMachine(config, this).also { machine = it }
    }

    /** The persisted combined-trigger settings and waiting counters (PRD §11). */
    fun loadTriggerState(): WallTriggerState = repository.loadTrigger()

    fun saveTriggerState(state: WallTriggerState) = repository.saveTrigger(state)

    /**
     * Evaluates the combined AND trigger against the persisted settings/counters
     * and the machine's manual selection. The caller feeds the result to
     * [AutoWallsMachine.onTrigger]; this never taps or upgrades on its own, and a
     * disabled machine still refuses the result at its own state guard.
     */
    fun evaluateTrigger(evidence: TriggerEvidence): TriggerEvaluation {
        val state = repository.loadTrigger()
        return AutoWallsTrigger.evaluate(
            state.config,
            machine?.selection ?: WallSelectionConfig(),
            state.counters,
            evidence
        )
    }

    override fun onSnapshot(snapshot: AutoWallsSnapshot) {
        if (snapshot.state == AutoWallsState.DISABLED) {
            repository.clearRun()
        } else {
            repository.saveRun(snapshot, now())
        }
    }

    override fun onEvent(event: WallEvent) {
        repository.record(event, now())
    }
}
