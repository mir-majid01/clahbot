package com.clahbot.walls

import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence + restart recovery (Auto Walls PRD §11). Configuration survives a
 * restart, a cut-off run is never resumed, and history is bounded and readable.
 */
class AutoWallsPersistenceTest {

    private lateinit var directory: File
    private lateinit var configFile: File
    private lateinit var runFile: File
    private lateinit var historyFile: File
    private lateinit var triggerFile: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-auto-walls").toFile()
        configFile = File(directory, "auto_walls_config.json")
        runFile = File(directory, "auto_walls_run.json")
        historyFile = File(directory, "auto_walls_history.json")
        triggerFile = File(directory, "auto_walls_trigger.json")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun repository() = AutoWallsRepository(configFile, runFile, historyFile)

    private fun candidate(
        id: String = "row:2",
        level: Int = 13,
        price: Long = 500_000,
        currency: String = "elixir"
    ) = WallCandidate(
        id = id,
        level = level,
        price = price,
        currency = currency,
        listIndex = 2,
        confidence = 0.9f
    )

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    private fun confirmationLines(level: Int, price: String) = listOf(
        line("Upgrade Wall to Level $level?", 300, 20, 900, 60),
        line("CONFIRM", 760, 560, 880, 590),
        line(price, 760, 592, 880, 622)
    )

    private fun currencyAt(box: OcrBox): String? = if (box.top in 588..630) "elixir" else null

    private fun passEvaluation(): TriggerEvaluation =
        TriggerEvaluation(listOf(TriggerCondition("attacks", true, "10 attacks since the last verified upgrade")))

    // ---- configuration -----------------------------------------------------

    @Test
    fun `configuration round-trips the manual selection preferences`() {
        repository().saveConfig(
            AutoWallsConfig(
                enabled = true,
                selection = WallSelectionConfig(
                    strategy = WallSelectionStrategy.CUSTOM,
                    customOrder = listOf(WallCriterion.HIGHEST_LEVEL, WallCriterion.CHEAPEST),
                    currency = WallCurrency.ELIXIR,
                    minConfidence = 0.8f
                ),
                allowTapping = true,
                maxScrolls = 6
            )
        )

        val loaded = repository().loadConfig()
        assertTrue(loaded.enabled)
        assertEquals(WallSelectionStrategy.CUSTOM, loaded.selection.strategy)
        assertEquals(
            listOf(WallCriterion.HIGHEST_LEVEL, WallCriterion.CHEAPEST),
            loaded.selection.customOrder
        )
        assertEquals(WallCurrency.ELIXIR, loaded.selection.currency)
        assertEquals(0.8f, loaded.selection.minConfidence, 0.0001f)
        assertEquals(6, loaded.maxScrolls)
        // Tapping preference round-trips with the rest of the config.
        assertTrue(loaded.allowTapping)
    }

    @Test
    fun `missing configuration falls back to safe defaults`() {
        val loaded = repository().loadConfig()
        assertFalse(loaded.enabled)
        assertEquals(WallSelectionStrategy.CHEAPEST_FIRST, loaded.selection.strategy)
        assertTrue(loaded.selection.customOrder.isEmpty())
        assertEquals(WallCurrency.BOTH, loaded.selection.currency)
        assertEquals(0.6f, loaded.selection.minConfidence, 0.0001f)
        assertEquals(4, loaded.maxScrolls)
        assertFalse(loaded.allowTapping)
    }

    @Test
    fun `a corrupt configuration is quarantined and defaults are used`() {
        configFile.writeText("{ this is not json")
        val loaded = repository().loadConfig()
        assertFalse(loaded.enabled)
        assertEquals(WallSelectionStrategy.CHEAPEST_FIRST, loaded.selection.strategy)
        assertTrue(
            directory.listFiles().orEmpty().any {
                it.name.startsWith("auto_walls_config.json.corrupt-")
            }
        )
    }

    // ---- run snapshot ------------------------------------------------------

    @Test
    fun `a run snapshot round-trips for diagnostics`() {
        val snapshot = AutoWallsSnapshot(AutoWallsState.VALIDATING_CONFIRMATION, candidate().copy(count = 317), 2)
        repository().saveRun(snapshot, 1_234L)
        assertEquals(snapshot, repository().loadRun())
    }

    @Test
    fun `a run before selection stores a null candidate`() {
        val snapshot = AutoWallsSnapshot(AutoWallsState.SCANNING_WALLS, null, 1)
        repository().saveRun(snapshot, 5L)
        assertEquals(snapshot, repository().loadRun())
    }

    @Test
    fun `an unknown persisted state is quarantined instead of crashing`() {
        runFile.writeText("""{"state":"A_FUTURE_STATE","level":13,"price":1,"currency":"gold"}""")
        assertNull(repository().loadRun())
        assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("auto_walls_run.json.corrupt-") })
    }

    // ---- restart recovery --------------------------------------------------

    @Test
    fun `recovery clears a pre-confirmation run and records the interruption once`() {
        repository().saveConfig(AutoWallsConfig(enabled = true))
        val c = candidate()
        repository().saveRun(AutoWallsSnapshot(AutoWallsState.VALIDATING_CONFIRMATION, c, 1), 777L)

        val recovered = repository().recover(999L)
        assertTrue(recovered.config.enabled)
        val interrupted = recovered.interrupted
        assertNotNull(interrupted)
        assertEquals(AutoWallsState.VALIDATING_CONFIRMATION, interrupted!!.state)
        assertEquals(c, interrupted.candidate)
        assertEquals(777L, interrupted.updatedAt)
        assertFalse(interrupted.tapMayHaveOccurred)

        val entry = repository().history().single()
        assertEquals(999L, entry.at)
        assertEquals(WallEventStatus.FAILED, entry.status)
        assertEquals(c.level, entry.level)
        assertTrue(entry.detail.contains("fresh scan required"))

        assertNull(repository().loadRun())
        assertNull(repository().recover(1000L).interrupted)
        assertEquals(1, repository().history().size)
    }

    @Test
    fun `recovery flags a run interrupted after the confirm tap`() {
        val c = candidate()
        repository().saveRun(AutoWallsSnapshot(AutoWallsState.VERIFYING_RESULT, c, 0), 50L)

        val interrupted = repository().recover(60L).interrupted
        assertNotNull(interrupted)
        assertTrue(interrupted!!.tapMayHaveOccurred)

        val entry = repository().history().single()
        assertEquals(WallEventStatus.FAILED, entry.status)
        assertTrue(entry.detail.contains("not retried"))
        assertEquals(c.price, entry.price)
        assertEquals(c.currency, entry.currency)
    }

    @Test
    fun `recovery ignores a finished or idle run without duplicating history`() {
        repository().saveRun(
            AutoWallsSnapshot(AutoWallsState.SAFETY_BLOCKED, candidate(), 4),
            10L
        )
        assertNull(repository().recover(20L).interrupted)
        assertTrue(repository().history().isEmpty())
        assertNull(repository().loadRun())

        repository().saveRun(AutoWallsSnapshot(AutoWallsState.COOLDOWN, candidate(), 0), 30L)
        assertNull(repository().recover(40L).interrupted)
        assertTrue(repository().history().isEmpty())
        assertNull(repository().loadRun())
    }

    // ---- history -----------------------------------------------------------

    @Test
    fun `history is bounded, newest first, and survives reload`() {
        val repo = repository()
        for (i in 0 until 205) {
            repo.record(WallEvent(WallEventStatus.VERIFIED, i, i.toLong(), "gold"), i.toLong())
        }
        val history = repository().history()
        assertEquals(200, history.size)
        assertEquals(204, history.first().level)
        assertEquals(5, history.last().level)
    }

    @Test
    fun `a corrupt history file is quarantined, not overwritten`() {
        historyFile.writeText("not json at all")
        repository().record(WallEvent(WallEventStatus.DETECTED, 14, 500_000, "elixir"))
        assertEquals(1, repository().history().size)
        assertTrue(
            directory.listFiles().orEmpty().any {
                it.name.startsWith("auto_walls_history.json.corrupt-")
            }
        )
    }

    // ---- session -----------------------------------------------------------

    @Test
    fun `a restart starts waiting with no candidate and requires a fresh scan`() {
        val repo = repository()
        repo.saveConfig(AutoWallsConfig(enabled = true, allowTapping = true))
        repo.saveRun(AutoWallsSnapshot(AutoWallsState.VERIFYING_RESULT, candidate(), 0), 10L)

        val session = AutoWallsSession(repo) { 20L }
        val machine = session.start()

        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, machine.state)
        assertNull(machine.snapshot().candidate)
        assertTrue(session.recovery!!.interrupted!!.tapMayHaveOccurred)
        assertEquals(AutoWallsMachine.Action.ScanRows, machine.onTrigger(passEvaluation()))
        // The persisted allowTapping=true must not turn taps back on.
        assertTrue(machine.onRows(listOf(candidate())) is AutoWallsMachine.Action.Stop)
    }

    @Test
    fun `the session persists each transition and records milestones`() {
        val repo = repository()
        repo.saveConfig(AutoWallsConfig(enabled = true))
        val machine = AutoWallsSession(repo) { 100L }.start()

        assertEquals(AutoWallsMachine.Action.ScanRows, machine.onTrigger(passEvaluation()))
        assertEquals(AutoWallsState.SCANNING_WALLS, repo.loadRun()?.state)

        assertEquals(AutoWallsMachine.Action.ScrollAndScan, machine.onRows(emptyList()))
        assertEquals(AutoWallsState.SCANNING_WALLS, repo.loadRun()?.state)

        machine.onRows(listOf(candidate()))
        assertEquals(AutoWallsState.SAFETY_BLOCKED, repo.loadRun()?.state)
        val statuses = repo.history().map { it.status }
        assertTrue(statuses.contains(WallEventStatus.DETECTED))
        assertTrue(statuses.contains(WallEventStatus.SELECTED))
        assertFalse(statuses.contains(WallEventStatus.ATTEMPTED))

        machine.disable()
        assertNull(repo.loadRun())
    }

    @Test
    fun `a completed upgrade is in history after restart`() {
        val repo = repository()
        repo.saveConfig(AutoWallsConfig(enabled = true, allowTapping = true))
        val machine = AutoWallsSession(repo) { 1L }.start(allowTapping = true)

        machine.onTrigger(passEvaluation())
        machine.onRows(listOf(candidate(level = 13, price = 500_000).copy(count = 12)))
        machine.onRowTapResult(true)
        machine.onConfirmation(confirmationLines(13, "500 000")) { currencyAt(it) }
        machine.onConfirmTapResult(true)
        machine.onVerification(listOf(candidate(level = 13, price = 500_000).copy(count = 11)))

        val statuses = repo.history().map { it.status }
        assertTrue(statuses.contains(WallEventStatus.ATTEMPTED))
        assertTrue(statuses.contains(WallEventStatus.VERIFIED))
        assertTrue(repo.history().first().detail.contains("12->11"))

        val restarted = AutoWallsSession(repo) { 2L }
        restarted.start()
        assertNull(restarted.recovery!!.interrupted)
        assertEquals(WallEventStatus.VERIFIED, repo.history().first().status)
    }

    @Test
    fun `an unverified attempt keeps its reason in history`() {
        val repo = repository()
        repo.saveConfig(AutoWallsConfig(enabled = true, allowTapping = true))
        val machine = AutoWallsSession(repo) { 1L }.start(allowTapping = true)

        machine.onTrigger(passEvaluation())
        machine.onRows(listOf(candidate(level = 13, price = 500_000).copy(count = 12)))
        machine.onRowTapResult(true)
        machine.onConfirmation(confirmationLines(13, "500 000")) { currencyAt(it) }
        machine.onConfirmTapResult(true)
        machine.onVerification(listOf(candidate(level = 13, price = 500_000).copy(count = 12)))

        val entry = repo.history().first()
        assertEquals(WallEventStatus.FAILED, entry.status)
        assertTrue(entry.detail.contains("unverified"))
        assertTrue(entry.detail.contains("unchanged"))
    }

    // ---- combined trigger config + counters -------------------------------

    @Test
    fun `the waiting attack count survives a restart and still waits`() {
        val repo = repository()
        repo.saveConfig(AutoWallsConfig(enabled = true))
        repo.saveTrigger(WallTriggerState(counters = WallTriggerCounters(attacksSinceVerified = 6)))
        val session = AutoWallsSession(repo) { 100L }
        val machine = session.start()
        val evidence = TriggerEvidence(
            enabled = true,
            resources = WallCounters.Reading(gold = 5_000_000, elixir = 5_000_000),
            now = 1_000_000
        )
        val evaluation = session.evaluateTrigger(evidence)
        assertFalse(evaluation.pass)
        assertEquals(AutoWallsMachine.Action.WaitForTrigger, machine.onTrigger(evaluation))
        assertTrue(machine.waitingReason!!.contains("6 of 10"))

        // A restart reloads the same waiting counters; it never resets or satisfies them.
        val restarted = AutoWallsSession(repo) { 200L }
        restarted.start()
        assertEquals(6, restarted.loadTriggerState().counters.attacksSinceVerified)
        assertFalse(restarted.evaluateTrigger(evidence).pass)
    }

    @Test
    fun `the session evaluates with the persisted config and the machine selection`() {
        val repo = repository()
        repo.saveConfig(
            AutoWallsConfig(enabled = true, selection = WallSelectionConfig(currency = WallCurrency.GOLD))
        )
        repo.saveTrigger(
            WallTriggerState(
                config = WallTriggerConfig(goldThreshold = 6_000_000),
                counters = WallTriggerCounters(attacksSinceVerified = 10)
            )
        )
        val session = AutoWallsSession(repo) { 100L }
        session.start()
        // Gold 5M is below the persisted 6M threshold; the large elixir balance is
        // irrelevant because the machine's selection is GOLD, not BOTH.
        val evaluation = session.evaluateTrigger(
            TriggerEvidence(
                enabled = true,
                resources = WallCounters.Reading(gold = 5_000_000, elixir = 9_000_000),
                now = 1_000_000
            )
        )
        assertFalse(evaluation.pass)
        assertTrue(evaluation.waitingReason!!.contains("below threshold"))
    }

    @Test
    fun `saveTriggerState persists waiting state for the next launch`() {
        val repo = repository()
        val session = AutoWallsSession(repo) { 100L }
        session.start()
        session.saveTriggerState(
            WallTriggerState(counters = WallTriggerCounters(attacksSinceVerified = 4, lastVerifiedAt = 500L))
        )
        val reloaded = AutoWallsSession(repository()) { 200L }
        assertEquals(4, reloaded.loadTriggerState().counters.attacksSinceVerified)
        assertEquals(500L, reloaded.loadTriggerState().counters.lastVerifiedAt)
    }

    @Test
    fun `trigger config and counters round-trip`() {
        val state = WallTriggerState(
            config = WallTriggerConfig(
                attacksBetweenUpgrades = 7,
                goldThreshold = 4_000_000,
                elixirThreshold = 3_000_000,
                goldReserve = 500_000,
                elixirReserve = 250_000,
                cooldownMillis = 45_000,
                maxUpgradesPerSession = 5,
                maxUpgradesPerRun = 2
            ),
            counters = WallTriggerCounters(attacksSinceVerified = 6, upgradesThisRun = 1, lastVerifiedAt = 123L)
        )
        repository().saveTrigger(state)
        assertEquals(state, repository().loadTrigger())
    }

    @Test
    fun `trigger defaults apply when no file exists`() {
        assertEquals(WallTriggerState(), repository().loadTrigger())
    }

    @Test
    fun `a corrupt trigger file is quarantined instead of crashing`() {
        triggerFile.writeText("{not json")
        assertEquals(WallTriggerState(), repository().loadTrigger())
        assertTrue(
            directory.listFiles().orEmpty().any { it.name.startsWith("auto_walls_trigger.json.corrupt-") }
        )
    }

    @Test
    fun `negative trigger values are sanitised on load`() {
        triggerFile.writeText(
            """{"config":{"attacksBetweenUpgrades":-5,"goldThreshold":-1,"cooldownMillis":-9,"maxUpgradesPerRun":-2},"counters":{"attacksSinceVerified":-3}}"""
        )
        val state = repository().loadTrigger()
        assertEquals(0, state.config.attacksBetweenUpgrades)
        assertEquals(0L, state.config.goldThreshold)
        assertEquals(0L, state.config.cooldownMillis)
        assertNull(state.config.maxUpgradesPerRun)
        assertEquals(0, state.counters.attacksSinceVerified)
    }

    // ---- run-start counter reset (PRD §5.4) --------------------------------

    @Test
    fun `session start resets the per-run upgrade counter and keeps verified-upgrade counters`() {
        val repo = repository()
        repo.saveTrigger(
            WallTriggerState(
                counters = WallTriggerCounters(
                    attacksSinceVerified = 7,
                    upgradesThisRun = 3,
                    lastVerifiedAt = 123L
                )
            )
        )

        AutoWallsSession(repo).start()

        val state = repo.loadTrigger()
        assertEquals(0, state.counters.upgradesThisRun)
        // The per-run limit is the only run-scoped counter; the attack and
        // cooldown state belongs to the last verified upgrade, not to a run.
        assertEquals(7, state.counters.attacksSinceVerified)
        assertEquals(123L, state.counters.lastVerifiedAt)

        // A second start stays at zero instead of growing or resetting others.
        AutoWallsSession(repo).start()
        val again = repo.loadTrigger()
        assertEquals(0, again.counters.upgradesThisRun)
        assertEquals(7, again.counters.attacksSinceVerified)
    }

    // ---- schema versioning --------------------------------------------------

    @Test
    fun `pre-versioning files load with their defaults`() {
        configFile.writeText("""{"enabled":true,"strategy":"CHEAPEST_FIRST"}""")
        val config = repository().loadConfig()
        assertTrue(config.enabled)
        assertEquals(WallSelectionStrategy.CHEAPEST_FIRST, config.selection.strategy)
    }

    @Test
    fun `files written by a newer build are quarantined and treated as absent`() {
        configFile.writeText("""{"schemaVersion":99,"enabled":true}""")
        val config = repository().loadConfig()
        assertFalse(config.enabled)
        assertFalse(configFile.exists())
        assertTrue(
            directory.listFiles().orEmpty().any { it.name.startsWith("auto_walls_config.json.corrupt-") }
        )
    }

    @Test
    fun `saved walls files carry the current schema version`() {
        val repo = repository()
        repo.saveTrigger(WallTriggerState())
        assertTrue(triggerFile.readText().contains("\"schemaVersion\":1"))
        // A current-version file reloads normally instead of being quarantined.
        assertEquals(WallTriggerState(), repo.loadTrigger())
        assertTrue(triggerFile.exists())
    }
}
