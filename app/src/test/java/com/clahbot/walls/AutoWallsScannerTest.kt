package com.clahbot.walls

import com.clahbot.game.GameState
import com.clahbot.game.ScreenObservation
import com.clahbot.gesture.ScanGate
import com.clahbot.ocr.BuilderFaceKind
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.ResourceKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The controlled scan loop: stop rules for empty viewports, bottom detection
 * that must be verified against actual list movement, scroll restore after
 * every outcome (cancellation included), and exclusive screen ownership while
 * the scanner scrolls the list. Everything runs through the real production
 * pipeline — [AutoWallsScanner] over a fake [WallDevice] whose frames are read
 * by the real [WallDiscovery].
 */
class AutoWallsScannerTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-auto-walls-scanner").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun repository() = AutoWallsRepository(
        File(directory, "auto_walls_config.json"),
        File(directory, "auto_walls_run.json"),
        File(directory, "auto_walls_history.json"),
        File(directory, "auto_walls_trigger.json"),
        File(directory, "auto_walls_scan.json")
    )

    // ---- frames ------------------------------------------------------------

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    private val header = line("Buildings & Heroes", 300, 100, 800, 140)

    /**
     * One wall-level group row: badge level, "Wall x N" label, price on one
     * line. A null price models a viewport where the price (and with it the
     * currency icon) was unreadable — a PARTIAL row.
     */
    private fun wallRow(level: Int, count: Int, price: String?, top: Int): List<OcrLine> {
        val lines = mutableListOf(
            line("$level", 40, top, 120, top + 40),
            line("Wall x $count", 200, top, 500, top + 40)
        )
        if (price != null) lines += line(price, 700, top, 1000, top + 40)
        return lines
    }

    private fun frame(
        lines: List<OcrLine>,
        currencyTop: Int? = null,
        face: BuilderFaceKind? = null
    ): WallFrame {
        val observation = ScreenObservation(
            state = GameState.UPGRADE_PANEL,
            confidence = 0.9f,
            frameWidth = 1080,
            frameHeight = 2400
        )
        val wallFrame = WallFrame(observation, listOf(header) + lines, 1080, 2400) { box ->
            if (currencyTop != null && box.top == currencyTop) ResourceKind.GOLD else null
        }
        wallFrame.builderFaceAt = { face }
        return wallFrame
    }

    // ---- fake device -------------------------------------------------------

    private class FakeDevice(vararg frames: WallFrame) : WallDevice {
        val queue = ArrayDeque(frames.toList())
        var scrolls = 0
        var scrollResult = true
        val scrollBackCalls = mutableListOf<Int>()
        val scrollUpCalls = mutableListOf<Int>()
        val firstObserved = CompletableDeferred<Unit>()
        val afterScroll = CompletableDeferred<Unit>()
        /** When true, wait() suspends until the caller cancels the scan. */
        var waitSuspends = false
        /**
         * When true the fake keeps showing its last frame after the queued
         * frames run out (a real screen does not go blank). Off by default so
         * the "capture failed after scrolling" path stays reachable.
         */
        var keepLastFrame = false
        /** Scan-gate state observed at every device call, for exclusivity checks. */
        val gateStateDuringCalls = mutableListOf<Boolean>()
        /** The scan owner id seen at every device call. */
        val scanOwnerIds = mutableListOf<Long>()

        override var scanOwnerId: Long = 0L

        override suspend fun observe(): WallFrame? {
            firstObserved.complete(Unit)
            gateStateDuringCalls += ScanGate.isActive()
            scanOwnerIds += scanOwnerId
            val next = queue.removeFirstOrNull() ?: if (keepLastFrame) lastObservedFrame else null
            if (next != null) lastObservedFrame = next
            return next
        }

        /**
         * A builder/field re-read captures the same screen again. It must not
         * consume the scripted queue, which models successive screens across
         * scrolls.
         */
        override suspend fun observeAgain(): WallFrame? {
            gateStateDuringCalls += ScanGate.isActive()
            scanOwnerIds += scanOwnerId
            return lastObservedFrame
        }

        private var lastObservedFrame: WallFrame? = null

        override suspend fun tap(box: com.clahbot.game.ScreenBox): Boolean = false

        override suspend fun scroll(): Boolean {
            scrolls++
            lastObservedFrame = queue.lastOrNull()
                ?: if (keepLastFrame) lastObservedFrame else null
            gateStateDuringCalls += ScanGate.isActive()
            scanOwnerIds += scanOwnerId
            afterScroll.complete(Unit)
            return scrollResult
        }

        override suspend fun scrollBack(steps: Int): Int {
            scrollBackCalls += steps
            scanOwnerIds += scanOwnerId
            return steps
        }

        override suspend fun scrollUp(): Boolean {
            scrollUpCalls += 1
            scanOwnerIds += scanOwnerId
            return true
        }

        override suspend fun wait(ms: Long) {
            if (waitSuspends) awaitCancellation()
        }

        override fun now(): Long = 1_000L

        override fun endWallTxn() = Unit
    }

    private fun scanner(device: WallDevice, logs: MutableList<String>) = AutoWallsScanner(
        repository = repository(),
        device = device,
        foreground = { "com.supercell.clashofclans" },
        log = { logs += it }
    )

    // ---- tests -------------------------------------------------------------

    @Test
    fun `a wall-free top still scrolls towards the real bottom`() {
        // Defect: the old rule stopped at the first viewport when no wall row was
        // found, so a list that starts with suggested upgrades never reached the
        // walls further down. The bottom is now proven by two consecutive
        // scrolls that leave the list signature unchanged.
        val top = frame(listOf(line("Suggested upgrades", 200, 300, 600, 340)))
        val middle = frame(listOf(line("Spring Trap 850 000", 200, 300, 600, 340)))
        val device = FakeDevice(top, middle, middle, middle)
        val logs = mutableListOf<String>()

        val report = runBlocking { scanner(device, logs).scan() }

        assertEquals(3, device.scrolls)
        assertEquals(4, report.viewports)
        assertTrue("the real bottom must be reached", report.bottomReached)
        assertTrue(report.candidates.isEmpty())
        assertNotNull(report.scanNote)
        assertTrue(report.scanNote!!.contains("no wall rows readable"))
        assertTrue(report.scanNote!!.contains("scrolled to its bottom"))
        assertEquals(listOf(3), device.scrollBackCalls)
    }

    @Test
    fun `a single viewport without wall rows keeps scanning instead of stopping`() {
        // Header is on screen but the visible section has no wall rows at all.
        // The scan must still try to scroll: a wall-free section is not the
        // bottom.
        val device = FakeDevice(
            frame(listOf(line("Suggested upgrades", 200, 300, 600, 340)))
        )
        val logs = mutableListOf<String>()

        val report = runBlocking { scanner(device, logs).scan() }

        assertEquals(1, device.scrolls)
        assertEquals(1, report.viewports)
        assertFalse(report.bottomReached)
        assertTrue(report.candidates.isEmpty())
        assertNotNull(report.scanNote)
        assertTrue(report.scanNote!!.contains("screen capture failed after scrolling"))
        assertEquals(listOf(1), device.scrollBackCalls)
    }

    /** A frame where the list header failed to read (the 10:52:55 failure). */
    private fun frameWithoutHeader(lines: List<OcrLine>, face: BuilderFaceKind? = null): WallFrame {
        val observation = ScreenObservation(
            state = GameState.UPGRADE_PANEL,
            confidence = 0.9f,
            frameWidth = 1080,
            frameHeight = 2400
        )
        val wallFrame = WallFrame(observation, lines, 1080, 2400) { null }
        wallFrame.builderFaceAt = { face }
        return wallFrame
    }

    @Test
    fun `a missed header read is retried with read-only captures instead of refusing`() {
        // Device recording: with the list plainly open, one scan refused while
        // the next attempt on the same screen succeeded. A flaky header OCR
        // pass must not abort the scan: re-capture read-only (never scroll
        // before those retries) and only refuse after the retries also miss.
        val device = FakeDevice(
            frameWithoutHeader(listOf(line("Suggested upgrades", 200, 300, 600, 340))),
            frameWithoutHeader(listOf(line("Suggested upgrades", 200, 300, 600, 340))),
            frame(emptyList())
        )
        val logs = mutableListOf<String>()

        val report = runBlocking { scanner(device, logs).scan() }

        // The scan proceeded to reading the list. The two header retries used
        // no scroll; the one scroll in the count is the ordinary "the visible
        // section had no wall rows, look further down" attempt.
        assertEquals(1, device.scrolls)
        assertEquals(1, report.viewports)
        assertEquals("no wall rows found on this frame", report.reason)
        assertTrue(report.scanNote!!.contains("capture failed"))
        assertEquals(listOf(1), device.scrollBackCalls)
    }

    @Test
    fun `a refused scan still reports the builders visible on the frame`() {
        // Device recording: on the refusal frame the `0/6 | 1/2 | 0/1` strip
        // was plainly visible, yet the panel said "Builders: not detected".
        val device = FakeDevice(
            frameWithoutHeader(
                listOf(
                    line("Home village", 100, 200, 500, 240),
                    OcrLine("0/6", OcrBox(600, 2200, 700, 2260), emptyList())
                ),
                face = BuilderFaceKind.REGULAR
            )
        )
        val logs = mutableListOf<String>()

        val report = runBlocking { scanner(device, logs).scan() }

        assertTrue(report.reason!!.contains("Buildings & Heroes"))
        assertEquals("NONE_AVAILABLE", report.builderStatus)
        assertEquals(0, report.builderFree)
        assertEquals(6, report.builderTotal)
        assertEquals(6, report.builderBusy)
        assertTrue(report.builderDetected)
        assertEquals(0.90f, report.builderConfidence, 0.0001f)
        assertEquals(0, device.scrolls)
        assertTrue(device.scrollBackCalls.isEmpty())
    }

    @Test
    fun `a merged duplicate does not end the scan while the list provably moves`() {
        // Viewport 2 re-reads the same wall group, but this time its price was
        // unreadable. It merges into the known row (added = 0) and the list
        // content DID change between the frames, so this must not be called the
        // bottom — the scan continues past it.
        val frameA = frame(wallRow(17, 317, "2 500 000", 300), currencyTop = 300)
        val frameB = frame(wallRow(17, 317, null, 300), currencyTop = 300)
        val device = FakeDevice(frameA, frameB)
        val logs = mutableListOf<String>()

        runBlocking {
            val job = async(start = CoroutineStart.DEFAULT) { scanner(device, logs).scan() }
            val report = job.await()
            // Every device call of the scan ran while the scan owned the
            // screen (recorded inside the fake, so it is race-free).
            assertTrue(
                "gate states recorded: " + device.gateStateDuringCalls,
                device.gateStateDuringCalls.isNotEmpty()
            )
            assertTrue(
                "gate held during every device call: " + device.gateStateDuringCalls,
                device.gateStateDuringCalls.all { it }
            )
            assertFalse(ScanGate.isActive())

            // The duplicate did NOT end the scan: it scrolled on past it
            // (two swipes) instead of declaring the bottom at viewport 2. The
            // queue then ran out and the loop ended with a capture note.
            assertEquals(2, device.scrolls)
            assertEquals(2, report.viewports)
            assertFalse(report.bottomReached)
            assertEquals(1, report.candidates.size)
            assertEquals(317, report.candidates.single().count)
            // The starting position is restored even on this early end.
            assertEquals(listOf(2), device.scrollBackCalls)
        }
    }

    @Test
    fun `the bottom is declared when two scrolls stop moving the list`() {
        // A completed swipe is not proof of movement: here the list clamps at
        // the end, so consecutive frames are identical. Two such scrolls in a
        // row are the proof; one is not (a late-loading list looks the same).
        val frame = frame(wallRow(17, 317, "2 500 000", 300), currencyTop = 300)
        val device = FakeDevice(frame, frame, frame)
        val logs = mutableListOf<String>()

        val report = runBlocking { scanner(device, logs).scan() }

        assertEquals(2, device.scrolls)
        assertEquals(3, report.viewports)
        assertTrue(report.bottomReached)
        assertEquals(1, report.candidates.size)
        assertEquals(listOf(2), device.scrollBackCalls)
        assertTrue(logs.any { it.contains("did not move the list") })
    }

    @Test
    fun `an unreadable duplicate followed by two unmoved lists still ends honestly`() {
        // A (price read) -> B (same row, price unreadable, list moved) ->
        // C (identical to B) -> D (identical again): the bottom is confirmed
        // only after the second unmoved scroll.
        val frameA = frame(wallRow(17, 317, "2 500 000", 300), currencyTop = 300)
        val frameB = frame(wallRow(17, 317, null, 300), currencyTop = 300)
        val device = FakeDevice(frameA, frameB, frameB, frameB)
        val logs = mutableListOf<String>()

        val report = runBlocking { scanner(device, logs).scan() }

        assertEquals(3, device.scrolls)
        assertEquals(4, report.viewports)
        assertTrue(report.bottomReached)
        assertEquals(listOf(3), device.scrollBackCalls)
    }

    @Test
    fun `wall-free viewports do not stop the scan before the real bottom`() {
        // Rows found first, then non-wall rows only. The scan keeps going until
        // the list itself stops moving, instead of stopping after a fixed
        // number of wall-free viewports.
        val frameA = frame(wallRow(17, 317, "2 500 000", 300), currencyTop = 300)
        val frameB = frame(listOf(line("Spring Trap 850 000", 200, 300, 600, 340)))
        val frameC = frame(listOf(line("Giant Bomb 1 600 000", 200, 300, 600, 340)))
        val frameD = frame(listOf(line("Air Bomb 300 000", 200, 300, 600, 340)))
        val device = FakeDevice(frameA, frameB, frameC, frameD, frameD, frameD)
        val logs = mutableListOf<String>()

        val report = runBlocking { scanner(device, logs).scan() }

        assertEquals(5, device.scrolls)
        assertEquals(6, report.viewports)
        assertTrue(report.bottomReached)
        assertEquals(1, report.candidates.size)
        assertEquals(listOf(5), device.scrollBackCalls)
        assertTrue(logs.any { it.contains("viewport=3 empty") })
    }

    @Test
    fun `a failed scroll stops the scan without a restore`() {
        val frameA = frame(wallRow(17, 317, "2 500 000", 300), currencyTop = 300)
        val device = FakeDevice(frameA)
        device.scrollResult = false
        val logs = mutableListOf<String>()

        val report = runBlocking { scanner(device, logs).scan() }

        assertEquals(1, device.scrolls)
        assertEquals(1, report.viewports)
        assertFalse(report.bottomReached)
        assertTrue(report.scanNote!!.contains("could not scroll"))
        assertTrue(device.scrollBackCalls.isEmpty())
    }

    @Test
    fun `cancelling mid-scan still restores the scroll position and releases the gate`() {
        // A decisive builder read on the first frame means no builder re-capture
        // wait happens before the scroll: the only suspension point the test
        // cancels at is the settle wait after the scroll.
        val frameA = frame(
            wallRow(17, 317, "2 500 000", 300) + line("5/6", 820, 1700, 900, 1760),
            currencyTop = 300,
            face = BuilderFaceKind.REGULAR
        )
        val frameB = frame(wallRow(16, 12, "1 000 000", 300), currencyTop = 300)
        val device = FakeDevice(frameA, frameB)
        device.waitSuspends = true
        val logs = mutableListOf<String>()

        runBlocking {
            val job = async { scanner(device, logs).scan() }
            // Deterministic stop: the scan has scrolled once and is waiting
            // for the list to settle when we cancel it.
            device.afterScroll.await()
            assertTrue(ScanGate.isActive())
            job.cancelAndJoin()

            assertEquals(1, device.scrolls)
            assertEquals(listOf(1), device.scrollBackCalls)
            assertFalse(ScanGate.isActive())
            assertTrue(logs.any { it.contains("restore scrolled=1") })
        }
    }

    @Test
    fun `the scan publishes its owner id so the shared authority recognises its own swipe`() {
        val frameA = frame(wallRow(17, 317, "2 500 000", 300), currencyTop = 300)
        val frameB = frame(wallRow(16, 12, "1 000 000", 300), currencyTop = 300)
        val device = FakeDevice(frameA, frameB)
        val logs = mutableListOf<String>()

        runBlocking { scanner(device, logs).scan() }

        // Every device call happened while the scan held the gate and knew its
        // own owner id, so the shared GestureAuthority can let the scan's
        // swipe through and refuse everybody else.
        assertTrue(device.scanOwnerIds.isNotEmpty())
        assertTrue(
            "scan owner id must be non-zero during the scan: " + device.scanOwnerIds,
            device.scanOwnerIds.all { it != 0L }
        )
        assertEquals(
            "the scan must use one owner id for the whole scan",
            1,
            device.scanOwnerIds.distinct().size
        )
    }

    @Test
    fun `the restore is verified by signature and corrected with upward swipes when it differs`() {
        // The list signature after the restore differs from the pre-scan top
        // signature: the swipe count is not proof, so the scanner must compare
        // signatures and apply corrective upward swipes until they match.
        val top = frame(wallRow(17, 317, "2 500 000", 300), currencyTop = 300)
        val middle = frame(wallRow(16, 12, "1 000 000", 300), currencyTop = 300)
        val device = FakeDevice(top, middle)
        device.keepLastFrame = true
        val logs = mutableListOf<String>()

        runBlocking { scanner(device, logs).scan() }

        // The restore ran once per scroll the scan performed.
        assertEquals(listOf(device.scrolls), device.scrollBackCalls)
        // The verification found a different list and corrected it.
        assertTrue(
            "a signature mismatch must be logged: " + logs,
            logs.any { it.contains("restore verify MISMATCH") }
        )
        assertTrue(
            "a corrective upward swipe must have been sent: " + logs,
            device.scrollUpCalls.isNotEmpty()
        )
        assertTrue(
            "the corrective swipe must be logged: " + logs,
            logs.any { it.contains("restore corrective swipe") }
        )
    }

    @Test
    fun `the restore is not corrected when the signature already matches`() {
        val top = frame(wallRow(17, 317, "2 500 000", 300), currencyTop = 300)
        val same = frame(wallRow(17, 317, "2 500 000", 300), currencyTop = 300)
        val device = FakeDevice(top, same)
        device.keepLastFrame = true
        val logs = mutableListOf<String>()

        runBlocking { scanner(device, logs).scan() }

        assertTrue(
            "a matching signature must be logged: " + logs,
            logs.any { it.contains("restore verify signature matches top") }
        )
        assertTrue(
            "no corrective swipe may be sent when the signature matches: " + logs,
            device.scrollUpCalls.isEmpty()
        )
    }
}
