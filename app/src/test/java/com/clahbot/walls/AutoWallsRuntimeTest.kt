package com.clahbot.walls

import com.clahbot.game.GameState
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import com.clahbot.ocr.BuilderFaceKind
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.ResourceKind
import com.clahbot.walls.BuilderValidationGate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.nio.file.Files

/**
 * Auto Walls 2.0 runtime wiring (PRD §3–§9). A fake [WallDevice] serves captured
 * frames and records taps/scrolls, so the whole scan → select → tap → confirm →
 * reserve re-check → verify → counter pipeline is exercised on the JVM. The real
 * Android adapter is the only untested glue. Production runs with allowTapping
 * false; the tapping tests open the gate ONLY in the fake to prove the downstream
 * wiring, never bypassing a safety or verification check.
 */
class AutoWallsRuntimeTest {

    /**
     * Every test here drives a fake device, so anything that runs longer than
     * this is a deadlock, not slowness. Without the rule a wedged coroutine
     * hangs the whole Gradle test task and no result XML is written.
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    @Before
    fun setUp() {
        // Enable builder validation gate for tests that exercise the full
        // upgrade flow. Real-world authorization stays OFF until device
        // evidence is manually validated.
        BuilderValidationGate.realFrameValidationEnabled = true
    }

    // ---- fake device -------------------------------------------------------

    private class FakeDevice : WallDevice {
        val frames = ArrayDeque<WallFrame>()
        var fallback: WallFrame? = null
        val taps = mutableListOf<ScreenBox>()
        var tapResult = true
        var scrollResult = true
        var scrolls = 0
        var clock = 1_000L
        val waits = mutableListOf<Long>()
        /** If > 0, wait() actually suspends for this many ms, allowing cancellation windows. */
        var waitDelayMs: Long = 0

        override var scanOwnerId: Long = 0L

        /** The frame the last [observe] served; re-reads return this same screen. */
        private var lastServed: WallFrame? = null

        override suspend fun observe(): WallFrame? {
            val next = if (frames.isNotEmpty()) frames.removeFirst() else fallback
            if (next != null) lastServed = next
            return next
        }

        /**
         * A field re-read captures the screen that is already showing again. It
         * must NOT consume the scripted queue: `frames` models successive
         * screens across scrolls, and a builder re-capture would otherwise eat
         * the next screen and make every later assertion lie.
         */
        override suspend fun observeAgain(): WallFrame? = lastServed ?: fallback

        override suspend fun tap(box: ScreenBox): Boolean {
            taps += box
            return tapResult
        }

        override suspend fun scroll(): Boolean {
            scrolls++
            return scrollResult
        }

        override suspend fun wait(ms: Long) {
            waits += ms
            clock += ms
            if (waitDelayMs > 0) delay(waitDelayMs)
        }

        override fun now(): Long = clock

        override fun endWallTxn() {
            // No-op for fake device; ownership tracking is in GestureAuthority.
        }
    }

    // ---- frame builders ----------------------------------------------------

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    private fun frame(
        state: GameState,
        lines: List<OcrLine>,
        classify: (OcrBox) -> ResourceKind? = { null },
        confirmButton: ScreenBox? = null,
        forbidden: List<ScreenBox> = emptyList(),
        width: Int = 1080,
        height: Int = 2400
    ): WallFrame {
        val allLines = lines + listOf(line("Builder 3", 100, 100, 200, 120))
        val observation = ScreenObservation(
            state = state, confidence = 0.9f, frameWidth = width, frameHeight = height,
            confirmButton = confirmButton, forbidden = forbidden
        )
        return WallFrame(observation, allLines, width, height, System.currentTimeMillis(), classify)
    }

    private val goldCounter = line("5 000 000", 700, 100, 1000, 160)
    private val classifyVillage: (OcrBox) -> ResourceKind? =
        { box -> if (box.top in 100..160) ResourceKind.GOLD else null }

    /**
     * A real village frame: the gold counter up top and a regular builder strip
     * at the bottom right whose left-most slot reads `6/6` with a regular face.
     * The builder evidence gate is fail-closed, so a village fixture without a
     * genuine fraction+face could never authorize an upgrade — and a test that
     * reaches the tap path must model that evidence honestly.
     */
    private fun village(): WallFrame {
        val frame = frame(
            GameState.HOME,
            listOf(goldCounter, line("6/6", 820, 1700, 900, 1760)),
            classifyVillage
        )
        frame.builderFaceAt = { BuilderFaceKind.REGULAR }
        return frame
    }

    /** One wall-level group row: badge level, "Wall x N" label, gold price. */
    private fun wallRow(level: Int, count: Int, price: String, top: Int = 300): List<OcrLine> = listOf(
        line("$level", 40, top, 120, top + 40),
        line("Wall x $count", 40, top + 50, 400, top + 100),
        line(price, 40, top + 110, 400, top + 160)
    )

    // The real icon sampler reads currency from the icon, not the y position, so
    // the fake classifies any left-side list price as gold wherever it scrolls.
    private val classifyList: (OcrBox) -> ResourceKind? =
        { box -> if (box.left < 500) ResourceKind.GOLD else null }

    private fun list(count: Int, level: Int = 17) =
        frame(GameState.UPGRADE_PANEL, wallRow(level, count, "2 500 000"), classifyList)

    private val confirmBox = ScreenBox(760, 560, 980, 620, kind = "confirm")

    private fun confirmation(level: Int = 17) = frame(
        GameState.UPGRADE_PANEL,
        listOf(
            line("Upgrade Wall to Level $level?", 300, 20, 900, 60),
            line("CONFIRM", 760, 560, 880, 590),
            line("2 500 000", 760, 592, 880, 622),
            goldCounter
        ),
        { box -> if (box.top in 100..160 || box.top in 588..630) ResourceKind.GOLD else null },
        confirmButton = confirmBox
    )

    // ---- session -----------------------------------------------------------

    private fun session(allowTapping: Boolean): Pair<AutoWallsSession, File> {
        val dir = Files.createTempDirectory("auto-walls-runtime").toFile()
        val repository = AutoWallsRepository(
            File(dir, "config.json"), File(dir, "run.json"), File(dir, "history.json"),
            File(dir, "trigger.json")
        )
        repository.saveConfig(AutoWallsConfig(enabled = true, allowTapping = allowTapping))
        repository.saveTrigger(
            WallTriggerState(
                config = WallTriggerConfig(
                    attacksBetweenUpgrades = 10,
                    goldThreshold = 1_000_000, elixirThreshold = 1_000_000,
                    goldReserve = 1_000_000, elixirReserve = 1_000_000
                ),
                counters = WallTriggerCounters(attacksSinceVerified = 10)
            )
        )
        val session = AutoWallsSession(repository)
        session.start(allowTapping = allowTapping)
        return session to dir
    }

    private fun runtime(
        session: AutoWallsSession,
        device: FakeDevice,
        allowRepeat: Boolean = false,
        maxRepeats: Int = 5,
        speed: Float = 1f
    ) = AutoWallsRuntime(session, device, speed = speed, allowRepeat = allowRepeat, maxRepeats = maxRepeats)

    // ---- fixture sanity ----------------------------------------------------

    @Test
    fun `the wall-list fixture yields one identified candidate`() {
        // If this fails, the runtime tests below can only ever fail downstream:
        // a fixture that does not parse cannot be selected or tapped.
        val rows = list(317).candidates()
        assertEquals(
            "fixture must produce exactly one identified wall: $rows",
            1,
            rows.count { it.state == WallRowState.IDENTIFIED }
        )
        assertEquals(17, rows.first().level)
        assertEquals(2_500_000L, rows.first().price)
        assertEquals("gold", rows.first().currency)
        assertEquals(WallRowState.IDENTIFIED, rows.first().state)
    }

    // ---- production gate ---------------------------------------------------

    @Test
    fun `with tapping disabled the runtime scans and selects but never taps`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = false)
        val device = FakeDevice()
        device.frames.addAll(listOf(village(), list(317)))
        device.fallback = village()
        val outcome = runtime(session, device).run()
        assertEquals(AutoWallsState.SAFETY_BLOCKED, outcome.state)
        assertTrue(outcome.reason.contains("tapping is disabled"))
        assertTrue("no tap may happen while gated", device.taps.isEmpty())
        assertEquals(0, outcome.verifiedUpgrades)
        dir.deleteRecursively()
    }

    @Test
    fun `an unreadable balance keeps the runtime waiting without scanning`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = false)
        val device = FakeDevice()
        // No counter line: the balance is unknown, so the trigger must wait.
        device.frames.add(frame(GameState.HOME, emptyList()))
        device.fallback = frame(GameState.HOME, emptyList())
        val outcome = runtime(session, device).run()
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, outcome.state)
        assertTrue(device.taps.isEmpty())
        dir.deleteRecursively()
    }

    // ---- manual run (controller "Run now") ---------------------------------

    @Test
    fun `a manual run discovers walls without waiting for the trigger`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = false)
        val device = FakeDevice()
        // The trigger needs 10 attacks; only 2 have been completed, so an
        // automatic run would sit waiting. Manual bypasses the counters only.
        val state = session.loadTriggerState()
        session.saveTriggerState(
            state.copy(counters = state.counters.copy(attacksSinceVerified = 2))
        )
        device.frames.addAll(listOf(village(), list(317)))
        device.fallback = village()
        val outcome = runtime(session, device).run(manual = true)
        // The trigger was bypassed; the tap gate was not: scan + select, then stop.
        assertEquals(AutoWallsState.SAFETY_BLOCKED, outcome.state)
        assertTrue(outcome.reason.contains("tapping is disabled"))
        assertTrue(device.taps.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `an automatic run still waits when the attack count is short`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = false)
        val device = FakeDevice()
        val state = session.loadTriggerState()
        session.saveTriggerState(
            state.copy(counters = state.counters.copy(attacksSinceVerified = 2))
        )
        device.frames.add(village())
        device.fallback = village()
        val outcome = runtime(session, device).run()
        assertEquals(AutoWallsState.WAITING_FOR_TRIGGER, outcome.state)
        assertTrue(outcome.reason.contains("attacks"))
        assertTrue(device.taps.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `a manual run is refused while Auto Walls is switched off`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = false)
        session.machine?.disable()
        val device = FakeDevice()
        val outcome = runtime(session, device).run(manual = true)
        assertEquals(AutoWallsState.DISABLED, outcome.state)
        assertTrue(device.taps.isEmpty())
        dir.deleteRecursively()
    }

    // ---- full pipeline (gate opened in the fake only) ----------------------

    @Test
    fun `a passing trigger discovers selects taps confirms and verifies from real OCR`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        device.frames.addAll(
            listOf(village(), list(317), confirmation(), confirmation(), list(316))
        )
        device.fallback = village()
        val outcome = runtime(session, device).run()
        assertEquals(1, outcome.verifiedUpgrades)
        // Two taps: the wall row label, then the confirm button.
        assertEquals(2, device.taps.size)
        assertEquals(ScreenBox(40, 350, 400, 400), device.taps[0])
        assertEquals(confirmBox, device.taps[1])
        assertTrue(session.machine!!.statuses().contains(WallEventStatus.VERIFIED))
        dir.deleteRecursively()
    }

    @Test
    fun `Stop before confirm tap cancels the pending confirmation`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        device.frames.addAll(listOf(village(), list(317), confirmation(), confirmation(), list(316)))
        device.fallback = village()
        // Give the runtime a cancellation window between row tap and confirm tap.
        device.waitDelayMs = 100

        val job = launch {
            runtime(session, device).run()
        }

        // Wait for the row tap to complete.
        while (device.taps.isEmpty()) {
            delay(10)
        }

        // Stop is pressed: cancel the runtime job.
        job.cancel()
        job.join()

        // Only the row tap must have happened; the confirm tap must NOT occur.
        assertTrue("Confirm tap must not execute after Stop",
            device.taps.size == 1)
        dir.deleteRecursively()
    }

    @Test
    fun `the row tap uses the fresh label box from the current scan`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        // The wall sits 200 px lower than the default row position.
        val scrolledList = frame(GameState.UPGRADE_PANEL, wallRow(17, 317, "2 500 000", top = 500), classifyList)
        device.frames.addAll(listOf(village(), scrolledList, confirmation(), confirmation(), list(316)))
        device.fallback = village()
        runtime(session, device).run()
        assertEquals(ScreenBox(40, 550, 400, 600), device.taps.first())
        dir.deleteRecursively()
    }

    @Test
    fun `a successful confirm tap is not a verified upgrade until the re-scan proves it`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        // Re-scan shows the count unchanged: the tap proved nothing.
        device.frames.addAll(listOf(village(), list(317), confirmation(), confirmation(), list(317)))
        device.fallback = village()
        val outcome = runtime(session, device).run()
        assertEquals(0, outcome.verifiedUpgrades)
        assertFalse(session.machine!!.statuses().contains(WallEventStatus.VERIFIED))
        // The attack counter must NOT reset on an unverified attempt.
        assertEquals(10, session.loadTriggerState().counters.attacksSinceVerified)
        dir.deleteRecursively()
    }

    // ---- reserve re-check (stage 5) ----------------------------------------

    @Test
    fun `the reserve re-check stops before confirm when the discounted price breaks it`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        // Balance fell to 3M: 3M - 2.5M = 0.5M, below the 1M reserve.
        val poorVillage = frame(
            GameState.HOME, listOf(line("3 000 000", 700, 100, 1000, 160)), classifyVillage
        )
        val poorConfirm = frame(
            GameState.UPGRADE_PANEL,
            listOf(
                line("Upgrade Wall to Level 17?", 300, 20, 900, 60),
                line("CONFIRM", 760, 560, 880, 590),
                line("2 500 000", 760, 592, 880, 622),
                line("3 000 000", 700, 100, 1000, 160)
            ),
            { box -> if (box.top in 100..160 || box.top in 588..630) ResourceKind.GOLD else null },
            confirmButton = confirmBox
        )
        device.frames.addAll(listOf(village(), list(317), confirmation(), poorConfirm))
        device.fallback = poorVillage
        val outcome = runtime(session, device).run()
        assertTrue(outcome.reason.contains("breaks reserve"))
        // Only the row was tapped; the confirm tap never happened.
        assertEquals(1, device.taps.size)
        assertEquals(0, outcome.verifiedUpgrades)
        dir.deleteRecursively()
    }

    @Test
    fun `the reserve re-check refuses an unreadable balance before confirm`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        val blindConfirm = frame(
            GameState.UPGRADE_PANEL,
            listOf(
                line("Upgrade Wall to Level 17?", 300, 20, 900, 60),
                line("CONFIRM", 760, 560, 880, 590),
                line("2 500 000", 760, 592, 880, 622)
            ),
            { box -> if (box.top in 588..630) ResourceKind.GOLD else null },
            confirmButton = confirmBox
        )
        device.frames.addAll(listOf(village(), list(317), confirmation(), blindConfirm))
        device.fallback = village()
        val outcome = runtime(session, device).run()
        assertTrue(outcome.reason.contains("not readable before confirm"))
        assertEquals(1, device.taps.size)
        dir.deleteRecursively()
    }

    // ---- never-press guard -------------------------------------------------

    @Test
    fun `a row overlapping a never-press box is refused before any tap`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        val guarded = frame(
            GameState.UPGRADE_PANEL, wallRow(17, 317, "2 500 000"), classifyList,
            forbidden = listOf(ScreenBox(0, 340, 500, 410, kind = "ring"))
        )
        device.frames.addAll(listOf(village(), guarded))
        device.fallback = village()
        val outcome = runtime(session, device).run()
        assertTrue(outcome.reason.contains("never-press"))
        assertTrue(device.taps.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `a popup before the row tap stops the runtime safely`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        val popup = frame(GameState.POPUP, wallRow(17, 317, "2 500 000"), classifyList)
        device.frames.addAll(listOf(village(), popup))
        device.fallback = village()
        val outcome = runtime(session, device).run()
        assertTrue(outcome.reason.contains("screen changed"))
        assertTrue(device.taps.isEmpty())
        dir.deleteRecursively()
    }

    // ---- counter feed (stage 7) --------------------------------------------

    @Test
    fun `a verified upgrade feeds the counter and resets the attack count`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        device.frames.addAll(listOf(village(), list(317), confirmation(), confirmation(), list(316)))
        device.fallback = village()
        runtime(session, device).run()
        val counters = session.loadTriggerState().counters
        assertEquals(0, counters.attacksSinceVerified)
        assertEquals(1, counters.upgradesThisRun)
        dir.deleteRecursively()
    }

    @Test
    fun `onAttackCompleted increments the persisted attack counter`() {
        val (session, dir) = session(allowTapping = false)
        val device = FakeDevice()
        val runtime = runtime(session, device)
        runtime.onAttackCompleted()
        runtime.onAttackCompleted()
        assertEquals(12, session.loadTriggerState().counters.attacksSinceVerified)
        dir.deleteRecursively()
    }

    // ---- repeat (stage 8, §5.5) --------------------------------------------

    @Test
    fun `repeat upgrades a second wall only after the first is verified`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        // Two full cycles; the second list shows a fresh wall at level 13.
        device.frames.addAll(
            listOf(
                village(), list(317), confirmation(), confirmation(), list(316),
                list(230, level = 13), confirmation(level = 13), confirmation(level = 13), list(229, level = 13)
            )
        )
        device.fallback = village()
        val outcome = runtime(session, device, allowRepeat = true, maxRepeats = 5).run()
        assertEquals(2, outcome.verifiedUpgrades)
        assertEquals(2, session.loadTriggerState().counters.upgradesThisRun)
        dir.deleteRecursively()
    }

    @Test
    fun `repeat is refused when the previous upgrade was not verified`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        // First attempt unverified (count unchanged); no repeat may follow.
        device.frames.addAll(listOf(village(), list(317), confirmation(), confirmation(), list(317)))
        device.fallback = village()
        val outcome = runtime(session, device, allowRepeat = true, maxRepeats = 5).run()
        assertEquals(0, outcome.verifiedUpgrades)
        // Exactly one row tap + one confirm tap; no second cycle.
        assertEquals(2, device.taps.size)
        dir.deleteRecursively()
    }

    @Test
    fun `repeat stops at the configured ceiling`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        // Two full verifiable cycles; maxRepeats = 1 must stop a third.
        device.frames.addAll(
            listOf(
                village(), list(317), confirmation(), confirmation(), list(316),
                list(315), confirmation(), confirmation(), list(314)
            )
        )
        // A third wall is available, so only the ceiling — not a missing wall — stops it.
        device.fallback = list(313)
        val outcome = runtime(session, device, allowRepeat = true, maxRepeats = 1).run()
        // maxRepeats = 1 → the first upgrade plus one repeat.
        assertEquals(2, outcome.verifiedUpgrades)
        // Two cycles = two row taps + two confirm taps; the ceiling blocks a third row tap.
        assertEquals(4, device.taps.size)
        dir.deleteRecursively()
    }

    // ---- execution speed (stage 8, §5.6) -----------------------------------

    @Test
    fun `speed scales the inter-action delays`() {
        val (session, dir) = session(allowTapping = false)
        val device = FakeDevice()
        val normal = runtime(session, device, speed = 1f)
        val fast = runtime(session, device, speed = 2f)
        assertEquals(1200L, normal.delayMs(1200L))
        assertEquals(600L, fast.delayMs(1200L))
        dir.deleteRecursively()
    }

    @Test
    fun `a faster speed shortens the waits between actions`(): Unit = runBlocking {
        val (session, dir) = session(allowTapping = true)
        val device = FakeDevice()
        device.frames.addAll(listOf(village(), list(317), confirmation(), confirmation(), list(316)))
        device.fallback = village()
        runtime(session, device, speed = 2f).run()
        assertTrue("every wait is scaled down", device.waits.all { it <= 600L })
        assertTrue(device.waits.isNotEmpty())
        dir.deleteRecursively()
    }

    // ---- reserve helper unit checks ----------------------------------------

    @Test
    fun `reserve recheck passes when the balance keeps the reserve after the cost`() {
        val result = AutoWallsReserve.recheck(5_000_000, 1_000_000, 2_500_000, "gold")
        assertTrue(result.ok)
    }

    @Test
    fun `reserve recheck refuses when the cost breaks the reserve`() {
        val result = AutoWallsReserve.recheck(3_000_000, 1_000_000, 2_500_000, "gold")
        assertFalse(result.ok)
        assertTrue(result.reason.contains("breaks reserve"))
    }

    @Test
    fun `reserve recheck refuses an unreadable balance or price`() {
        assertFalse(AutoWallsReserve.recheck(null, 1_000_000, 2_500_000, "gold").ok)
        assertFalse(AutoWallsReserve.recheck(5_000_000, 1_000_000, 0, "gold").ok)
    }

    private fun AutoWallsMachine.statuses(): List<WallEventStatus> = events.map { it.status }
}