package com.clahbot.walls

import com.clahbot.game.GameState
import com.clahbot.game.ScreenObservation
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.ResourceKind
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
 * The scan-only read pass (Auto Walls 2.0 Phase 1 UI). The report is produced by
 * replaying a captured frame through the real discovery + ranking code, so the
 * tests prove the UI shows exactly what the pipeline would select — and that a
 * report can never claim tapping is allowed. Persistence is the last-scan
 * diagnostic file; corrupt content is quarantined, never trusted.
 */
class AutoWallsScanTest {

    private lateinit var directory: File
    private lateinit var scanFile: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-auto-walls-scan").toFile()
        scanFile = File(directory, "auto_walls_scan.json")
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
        scanFile
    )

    // ---- frames ------------------------------------------------------------

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, OcrBox(left, top, right, bottom), emptyList())

    /** One wall-level group row: badge level, "Wall x N" label, price. */
    private fun wallRow(level: Int, count: Int, price: String, top: Int): List<OcrLine> = listOf(
        line("$level", 40, top, 120, top + 40),
        line("Wall x $count", 40, top + 50, 400, top + 100),
        line(price, 40, top + 110, 400, top + 160)
    )

    private fun frame(lines: List<OcrLine>, kinds: Map<Int, ResourceKind> = emptyMap()): WallFrame {
        val observation = ScreenObservation(
            state = GameState.UPGRADE_PANEL,
            confidence = 0.9f,
            frameWidth = 1080,
            frameHeight = 2400
        )
        return WallFrame(observation, lines, 1080, 2400) { box -> kinds[box.top] }
    }

    private val rowTops = listOf(300, 520, 740, 960)

    /** L13 elixir 750k, L17 gold 2.5M, L14 elixir 500k, L16 elixir 1M. */
    private fun fourWalls(): WallFrame = frame(
        wallRow(13, 83, "750 000", rowTops[0]) +
            wallRow(17, 317, "2 500 000", rowTops[1]) +
            wallRow(14, 230, "500 000", rowTops[2]) +
            wallRow(16, 12, "1 000 000", rowTops[3]),
        mapOf(
            rowTops[0] + 110 to ResourceKind.ELIXIR,
            rowTops[1] + 110 to ResourceKind.GOLD,
            rowTops[2] + 110 to ResourceKind.ELIXIR,
            rowTops[3] + 110 to ResourceKind.ELIXIR
        )
    )

    private fun config(
        strategy: WallSelectionStrategy = WallSelectionStrategy.CHEAPEST_FIRST,
        customOrder: List<WallCriterion> = emptyList(),
        currency: WallCurrency = WallCurrency.BOTH,
        minConfidence: Float = 0.6f
    ) = AutoWallsConfig(
        selection = WallSelectionConfig(strategy, customOrder, currency, minConfidence)
    )

    // ---- balances and affordable walls --------------------------------------

    private val goldCounter = line("5 000 000", 700, 100, 1000, 160)
    private val elixirCounter = line("2 000 000", 700, 170, 1000, 230)
    private val counterKinds = mapOf(
        100 to ResourceKind.GOLD,
        170 to ResourceKind.ELIXIR
    )

    /** The four wall rows plus a readable gold (5M) and elixir (2M) counter strip. */
    private fun villageWithWalls(): WallFrame = frame(
        listOf(goldCounter, elixirCounter) +
            wallRow(13, 83, "750 000", rowTops[0]) +
            wallRow(17, 317, "2 500 000", rowTops[1]) +
            wallRow(14, 230, "500 000", rowTops[2]) +
            wallRow(16, 12, "1 000 000", rowTops[3]),
        counterKinds + mapOf(
            rowTops[0] + 110 to ResourceKind.ELIXIR,
            rowTops[1] + 110 to ResourceKind.GOLD,
            rowTops[2] + 110 to ResourceKind.ELIXIR,
            rowTops[3] + 110 to ResourceKind.ELIXIR
        )
    )

    private fun row(
        level: Int,
        price: Long,
        currency: String,
        count: Int? = null
    ) = WallCandidate(
        id = "row:$level", level = level, price = price, currency = currency,
        listIndex = 0, confidence = 1f, count = count
    )

    @Test
    fun `analyze records the gold and elixir balances from the counters`() {
        val report = AutoWallsScan.analyze(villageWithWalls(), config(), at = 1L)
        assertEquals(5_000_000L, report.goldBalance)
        assertEquals(2_000_000L, report.elixirBalance)

        // A frame without counter lines must not invent balances.
        val blind = AutoWallsScan.analyze(fourWalls(), config(), at = 2L)
        assertNull(blind.goldBalance)
        assertNull(blind.elixirBalance)
        assertNull(blind.affordableGold)
        assertNull(blind.affordableElixir)
    }

    @Test
    fun `affordable walls walk the cheapest rows first`() {
        val gold = listOf(
            row(17, 2_500_000L, "gold", count = 317),
            row(15, 1_000_000L, "gold", count = 12)
        )
        // 5M: the 1M group buys 5, then the 2.5M group is out of reach.
        assertEquals(5L, AutoWallsScan.affordableWalls(gold, 5_000_000L))
        // 9M buys 9 walls from the 1M group alone, spending everything.
        assertEquals(9L, AutoWallsScan.affordableWalls(gold, 9_000_000L))
        // 15M: all 12 of the 1M group, then 1 wall at 2.5M, and 0.5M buys no more.
        assertEquals(13L, AutoWallsScan.affordableWalls(gold, 15_000_000L))
        // An unreadable balance says nothing; a balance below every price buys none.
        assertNull(AutoWallsScan.affordableWalls(gold, null))
        assertEquals(0L, AutoWallsScan.affordableWalls(gold, 500_000L))
        // A row without a read count counts as a single wall.
        assertEquals(
            1L,
            AutoWallsScan.affordableWalls(listOf(row(13, 750_000L, "elixir")), 2_000_000L)
        )
        // An invalid price never divides by zero.
        assertEquals(0L, AutoWallsScan.affordableWalls(listOf(row(13, 0L, "elixir", 10)), 2_000_000L))
    }

    @Test
    fun `analyze computes the affordable wall count per currency`() {
        val report = AutoWallsScan.analyze(villageWithWalls(), config(), at = 1L)
        // Gold 5M vs the single 2.5M gold row: 2 walls.
        assertEquals(2L, report.affordableGold)
        // Elixir 2M buys 4 walls from the 500k group alone.
        assertEquals(4L, report.affordableElixir)
    }

    // ---- analysis ----------------------------------------------------------

    @Test
    fun `analyze ranks every row and selects the cheapest`() {
        val report = AutoWallsScan.analyze(fourWalls(), config(), at = 1_000L)

        assertEquals(1_000L, report.at)
        assertTrue(report.gameForeground)
        assertTrue(report.captured)
        assertEquals("com.supercell.clashofclans", report.foregroundPackage)
        assertEquals(4, report.candidates.size)
        assertEquals(4, report.eligible.size)
        assertEquals(12, report.lineCount)
        assertEquals("UPGRADE_PANEL", report.screenState)
        assertEquals(0.9f, report.screenConfidence, 0.0001f)
        assertEquals(500_000L, report.eligible.first().price)
        assertEquals(14, report.selected?.level)
        assertEquals("elixir", report.selected?.currency)
        assertEquals(WallSelectionStrategy.CHEAPEST_FIRST, report.strategy)
        assertEquals(WallCurrency.BOTH, report.currency)
        assertEquals(0.6f, report.minConfidence, 0.0001f)
        assertNull(report.reason)
        assertFalse(report.allowTapping)
    }

    @Test
    fun `analyze applies the pay with filter`() {
        val gold = AutoWallsScan.analyze(fourWalls(), config(currency = WallCurrency.GOLD), at = 1L)
        assertEquals(1, gold.eligible.size)
        assertEquals(17, gold.selected?.level)
        assertEquals("gold", gold.selected?.currency)
        assertNull(gold.reason)

        val elixir = AutoWallsScan.analyze(fourWalls(), config(currency = WallCurrency.ELIXIR), at = 2L)
        assertEquals(3, elixir.eligible.size)
        assertEquals(14, elixir.selected?.level)
    }

    @Test
    fun `analyze drops low confidence and unknown currency rows`() {
        val mixed = frame(
            wallRow(13, 83, "750 000", rowTops[0]) +
                line("Wall x 50", 40, rowTops[1] + 50, 400, rowTops[1] + 100),
            mapOf(rowTops[0] + 110 to ResourceKind.ELIXIR)
        )
        val report = AutoWallsScan.analyze(mixed, config(), at = 1L)
        assertEquals(2, report.candidates.size)
        assertEquals(1, report.eligible.size)
        assertEquals(13, report.selected?.level)

        val unknownOnly = frame(
            wallRow(13, 83, "100 000", rowTops[0]),
            emptyMap()
        )
        val unknown = AutoWallsScan.analyze(unknownOnly, config(), at = 2L)
        assertNull(unknown.selected)
        assertTrue(unknown.eligible.isEmpty())
        assertNotNull(unknown.reason)
        assertTrue(unknown.reason!!.contains("none pass"))
        assertTrue(unknown.reason!!.contains("confidence"))
    }

    @Test
    fun `analyze honours a custom priority order`() {
        val report = AutoWallsScan.analyze(
            fourWalls(),
            config(
                strategy = WallSelectionStrategy.CUSTOM,
                customOrder = listOf(WallCriterion.HIGHEST_LEVEL, WallCriterion.CHEAPEST)
            ),
            at = 1L
        )
        assertEquals(17, report.selected?.level)
        assertEquals(2_500_000L, report.selected?.price)
    }

    @Test
    fun `analyze explains an empty frame`() {
        val report = AutoWallsScan.analyze(frame(emptyList()), config(), at = 1L)
        assertTrue(report.captured)
        assertTrue(report.candidates.isEmpty())
        assertNull(report.selected)
        assertNotNull(report.reason)
        assertTrue(report.reason!!.contains("no wall rows"))
        assertFalse(report.allowTapping)
    }

    @Test
    fun `refused scan names the foreground app and selects nothing`() {
        val report = AutoWallsScan.refused("com.facebook.katana", config(), at = 7L)
        assertFalse(report.gameForeground)
        assertFalse(report.captured)
        assertEquals("com.facebook.katana", report.foregroundPackage)
        assertTrue(report.candidates.isEmpty())
        assertNull(report.selected)
        assertNotNull(report.reason)
        assertTrue(report.reason!!.contains("com.facebook.katana"))
        assertFalse(report.allowTapping)
    }

    @Test
    fun `capture failure is a report not a crash`() {
        val report = AutoWallsScan.captureFailed(AutoWallsScan.GAME_PACKAGE, config(), at = 8L)
        assertTrue(report.gameForeground)
        assertFalse(report.captured)
        assertNull(report.selected)
        assertNotNull(report.reason)
        assertTrue(report.reason!!.contains("capture"))
        assertFalse(report.allowTapping)
    }

    // ---- persistence -------------------------------------------------------

    @Test
    fun `scan report round trips through disk`() {
        val repo = repository()
        val report = AutoWallsScan.analyze(fourWalls(), config(currency = WallCurrency.ELIXIR), at = 99L)
        repo.saveScan(report)

        val loaded = repo.loadScan()
        assertNotNull(loaded)
        loaded!!
        assertEquals(report.at, loaded.at)
        assertEquals(report.foregroundPackage, loaded.foregroundPackage)
        assertTrue(loaded.gameForeground)
        assertTrue(loaded.captured)
        assertEquals(report.frameWidth, loaded.frameWidth)
        assertEquals(report.frameHeight, loaded.frameHeight)
        assertEquals(report.lineCount, loaded.lineCount)
        assertEquals(report.candidates.size, loaded.candidates.size)
        assertEquals(report.candidates.first().level, loaded.candidates.first().level)
        assertEquals(report.eligible.size, loaded.eligible.size)
        assertEquals(report.selected?.id, loaded.selected?.id)
        assertEquals(report.reason, loaded.reason)
        assertEquals(report.strategy, loaded.strategy)
        assertEquals(report.currency, loaded.currency)
        assertEquals(report.minConfidence, loaded.minConfidence, 0.0001f)
        assertFalse(loaded.allowTapping)
    }

    @Test
    fun `corrupt scan file is quarantined`() {
        val repo = repository()
        scanFile.writeText("this is not json")

        assertNull(repo.loadScan())
        val quarantined = directory.listFiles().orEmpty()
            .any { it.name.startsWith("auto_walls_scan.json.corrupt-") }
        assertTrue("expected a quarantined copy", quarantined)
    }

    @Test
    fun `stored scan values are sanitised`() {
        val repo = repository()
        scanFile.writeText(
            """
            {"at":123,"foregroundPackage":"com.supercell.clashofclans","gameForeground":true,
             "captured":true,"frameWidth":1080,"frameHeight":2400,"lineCount":3,
             "candidates":[{"id":"row:0","level":13,"price":-5,"currency":"elixir",
                            "listIndex":-2,"confidence":4.0,"count":-1}],
             "eligible":[],"selected":null,"reason":null,"strategy":"NOPE",
             "currency":"NOPE","minConfidence":9.0,"allowTapping":true,
             "goldBalance":-1,"elixirBalance":7}
            """.trimIndent()
        )

        val loaded = repo.loadScan()
        assertNotNull(loaded)
        loaded!!
        assertEquals(1, loaded.candidates.size)
        val c = loaded.candidates.first()
        assertEquals(0L, c.price)
        assertEquals(0, c.listIndex)
        assertEquals(1f, c.confidence, 0.0001f)
        assertNull(c.count)
        assertEquals(WallSelectionStrategy.CHEAPEST_FIRST, loaded.strategy)
        assertEquals(WallCurrency.BOTH, loaded.currency)
        assertEquals(1f, loaded.minConfidence, 0.0001f)
        assertFalse("a stored report must never claim tapping is allowed", loaded.allowTapping)
        assertNull("a negative stored balance is garbage, not zero", loaded.goldBalance)
        assertEquals(7L, loaded.elixirBalance)
    }

    @Test
    fun `stored scan keeps the balances and affordable counts`() {
        val repo = repository()
        repo.saveScan(AutoWallsScan.analyze(villageWithWalls(), config(), at = 5L))

        val loaded = repo.loadScan()
        assertNotNull(loaded)
        loaded!!
        assertEquals(5_000_000L, loaded.goldBalance)
        assertEquals(2_000_000L, loaded.elixirBalance)
        assertEquals(2L, loaded.affordableGold)
        assertEquals(4L, loaded.affordableElixir)
    }

    @Test
    fun `a saved config never restores tapping`() {
        val repo = repository()
        repo.saveConfig(AutoWallsConfig(enabled = true, allowTapping = true))
        assertFalse(repo.loadConfig().allowTapping)
    }
}
