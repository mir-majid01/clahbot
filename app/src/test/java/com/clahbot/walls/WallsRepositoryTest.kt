package com.clahbot.walls

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Loading is where the old wall-line release meets the new one. Nothing here
 * may throw: a half-read file would otherwise leave the app with a mix of two
 * configuration eras.
 */
class WallsRepositoryTest {

    private lateinit var directory: File
    private lateinit var file: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-walls").toFile()
        file = File(directory, "walls.json")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun repository() = WallsRepository(file)

    private fun write(json: String) {
        file.writeText(json)
    }

    @Test
    fun `saves and reloads the settings`() = runBlocking {
        val repository = repository()
        repository.updateSettings {
            it.copy(
                enabled = true,
                wallMacroId = 17L,
                resourceMode = WallResourceMode.ELIXIR,
                costPerWall = 750_000L,
                minReserve = 2_000_000L,
                allowRepeatUpgrades = true,
                testedWallMacroId = 17L
            )
        }

        val reloaded = repository().settings()
        assertTrue(reloaded.enabled)
        assertEquals(17L, reloaded.wallMacroId)
        assertEquals(WallResourceMode.ELIXIR, reloaded.resourceMode)
        assertEquals(750_000L, reloaded.costPerWall)
        assertEquals(2_000_000L, reloaded.minReserve)
        assertTrue(reloaded.allowRepeatUpgrades)
        assertTrue(reloaded.isTested(17L))
    }

    @Test
    fun `reconciling clears a wall selection whose macro was deleted`() {
        val settings = WallSettings(wallMacroId = 17L, testedWallMacroId = 17L, enabled = true)

        val reconciled = settings.reconciled(setOf(17L, 23L))
        assertEquals(17L, reconciled.wallMacroId)
        assertEquals(17L, reconciled.testedWallMacroId)

        val gone = settings.reconciled(setOf(23L))
        assertEquals(-1L, gone.wallMacroId)
        assertEquals(-1L, gone.testedWallMacroId)
        // Deleting the macro must not silently turn the feature off.
        assertTrue(gone.enabled)
    }

    @Test
    fun `reconciling leaves an empty selection and unrelated settings alone`() {
        val settings = WallSettings(costPerWall = 500L, minReserve = 1_000L)
        val reconciled = settings.reconciled(emptySet())
        assertEquals(-1L, reconciled.wallMacroId)
        assertEquals(-1L, reconciled.testedWallMacroId)
        assertEquals(500L, reconciled.costPerWall)
        assertEquals(1_000L, reconciled.minReserve)
        assertEquals(settings, reconciled)
    }

    @Test
    fun `a wall-line file keeps the settings the new system can still use`() {
        write(
            """
            {"settings":{"enabled":true,"goldCost":600000,"elixirCost":600000,
            "goldEnabled":true,"elixirEnabled":false,"reserveGold":1000000,
            "reserveElixir":0,"thresholdGold":5000000,"thresholdElixir":0,
            "priority":"FULLER_FIRST","maxPerCycle":40,"wallLevel":9,"capSession":0,
            "capDay":0,"cameraMacroId":12,"line":{"ax":0.2,"ay":0.3,"bx":0.7,"by":0.4,
            "totalWalls":30,"wallsDone":4},"camera":{"landmarkLeft":0.4,
            "landmarkTop":0.5,"landmarkRight":0.45,"landmarkBottom":0.54,
            "patchFile":"landmark.png"},
            "history":[{"at":1,"mode":"LIVE","resource":"GOLD","walls":1,
            "price":600000,"expectedPrice":600000,"balanceBefore":9000000,
            "balanceAfter":8400000,"status":"ok"}]}}
            """.trimIndent()
        )

        val settings = repository().settings()
        // The switch, the price and the reserve still mean the same thing.
        assertTrue(settings.enabled)
        assertEquals(600_000L, settings.costPerWall)
        assertEquals(1_000_000L, settings.minReserve)
        assertEquals(WallPriority.GOLD_FIRST, settings.priority)
        // The geometry-era state cannot survive: there is no macro to point at
        // and no line to read history from.
        assertEquals(-1L, settings.wallMacroId)
        assertEquals(-1L, settings.testedWallMacroId)
        assertEquals(WallResourceMode.BOTH, settings.resourceMode)
        assertTrue(settings.history.isEmpty())
    }

    @Test
    fun `a legacy file with only elixir configured still gets a cost`() {
        write("""{"settings":{"enabled":false,"goldCost":0,"elixirCost":850000}}""")

        val settings = repository().settings()
        assertFalse(settings.enabled)
        assertEquals(850_000L, settings.costPerWall)
    }

    @Test
    fun `a file missing fields still gets the defaults the model declares`() {
        write("""{"settings":{"enabled":true,"wallMacroId":9,"costPerWall":1000000}}""")

        val settings = repository().settings()
        assertTrue(settings.enabled)
        assertEquals(9L, settings.wallMacroId)
        assertEquals(1_000_000L, settings.costPerWall)
        assertEquals(WallResourceMode.BOTH, settings.resourceMode)
        assertEquals(WallPriority.GOLD_FIRST, settings.priority)
        assertEquals(-1L, settings.testedWallMacroId)
        assertEquals(0L, settings.reserve())
        assertTrue(settings.history.isEmpty())
    }

    @Test
    fun `truncated json falls back to defaults instead of throwing`() {
        write("""{"settings":{"enabled":true,"wallMa""")

        val settings = repository().settings()
        assertFalse(settings.enabled)
        assertEquals(-1L, settings.wallMacroId)
    }

    @Test
    fun `an unreadable file is preserved aside instead of left to be overwritten`() {
        // A directory at the file path exists but cannot be read as text: the
        // read-failure branch. Falling back to defaults is fine, but the original
        // must be moved aside so the next save cannot clobber recoverable bytes.
        assertTrue(file.mkdir())

        val settings = repository().settings()
        assertFalse(settings.enabled)
        assertFalse("the unreadable walls file was left in place to be overwritten", file.exists())
        val quarantined = directory.listFiles()?.any { it.name.contains("corrupt") } ?: false
        assertTrue("the unreadable walls file was not preserved", quarantined)
    }

    @Test
    fun `a corrupt file is preserved aside instead of left to be overwritten`() {
        write("{ this is not json")

        val settings = repository().settings()
        assertFalse(settings.enabled)
        val quarantined = directory.listFiles()?.any { it.name.contains("corrupt") } ?: false
        assertTrue("the corrupt walls file was not preserved", quarantined)
    }

    @Test
    fun `an unknown resource mode keeps the default`() {
        write("""{"settings":{"resourceMode":"GEM","costPerWall":500}}""")

        assertEquals(WallResourceMode.BOTH, repository().settings().resourceMode)
        assertEquals(500L, repository().settings().costPerWall)
    }

    @Test
    fun `history keeps only entries written by the current model`() {
        write(
            """
            {"settings":{"wallMacroId":4,"history":[
            {"at":1730000000000,"macroName":"Walls lvl 9","resource":"GOLD",
            "goldBefore":8000000,"goldAfter":7000000,"elixirBefore":9000000,
            "elixirAfter":9000000,"status":"ran","detail":"ran \"Walls lvl 9\""},
            {"at":1730000000001,"mode":"TEST","resource":"GOLD","walls":1,
            "price":600000,"status":"ok"},
            {"at":1730000000002,"macroName":"Walls lvl 8","resource":"PLASMA",
            "status":"ran","detail":""}]}}
            """.trimIndent()
        )

        val history = repository().settings().history
        assertEquals(1, history.size)
        val entry = history.single()
        assertEquals("Walls lvl 9", entry.macroName)
        assertEquals(WallTrigger.GOLD, entry.resource)
        assertEquals(1_000_000L, entry.spentGold)
        assertEquals(0L, entry.spentElixir)
        assertEquals(1730000000000L, entry.at)
    }

    @Test
    fun `a run is appended and the history stays bounded`() {
        val repository = repository()
        repeat(60) { index ->
            repository.recordRun(
                WallRunLog(
                    at = 1_000L + index,
                    macroName = "Walls",
                    resource = WallTrigger.ELIXIR,
                    goldBefore = null,
                    goldAfter = null,
                    elixirBefore = 5_000L,
                    elixirAfter = 4_000L,
                    status = "ran"
                )
            )
        }

        val settings = repository().settings()
        assertEquals(50, settings.history.size)
        assertEquals(1_059L, settings.history.last().at)
        assertNull(settings.history.last().goldBefore)
    }

    @Test
    fun `clear history leaves the rest of the settings alone`() {
        val repository = repository()
        repository.updateSettings { it.copy(costPerWall = 250_000L) }
        repository.recordRun(
            WallRunLog(
                at = 5L, macroName = "Walls", resource = WallTrigger.GOLD,
                goldBefore = 1L, goldAfter = 0L, elixirBefore = 1L, elixirAfter = 1L,
                status = "ran"
            )
        )

        repository.clearHistory()

        assertTrue(repository().settings().history.isEmpty())
        assertEquals(250_000L, repository().settings().costPerWall)
    }

    @Test
    fun `a migrated file stays enabled after the first save`() {
        write("""{"settings":{"enabled":true,"goldCost":600000,"reserveGold":1000000}}""")

        val repository = repository()
        assertTrue("the wall-line switch has to carry over", repository.settings().enabled)

        repository.updateSettings { it.copy(costPerWall = 700_000L) }

        val reloaded = repository().settings()
        assertTrue(
            "saving once must not turn Auto Walls off again on the next load",
            reloaded.enabled
        )
        assertEquals(700_000L, reloaded.costPerWall)
        assertNull(repository.writeError)
    }

    @Test
    fun `a failed write keeps the previous file readable`() {
        val repository = repository()
        repository.updateSettings { it.copy(costPerWall = 100L) }
        val good = file.readText()

        val broken = WallsRepository(File(directory, "does/not/exist/walls.json"))
        broken.updateSettings { it.copy(costPerWall = 999L) }

        assertEquals(100L, repository().settings().costPerWall)
        assertTrue(file.readText() == good)
        assertFalse(File(directory, "walls.json.tmp").exists())
    }

    @Test
    fun `a failed write is reported instead of silently lost`() {
        val blocker = File(directory, "blocker")
        blocker.writeText("not a directory")
        val repository = WallsRepository(File(blocker, "walls.json"))

        repository.updateSettings { it.copy(enabled = true) }

        assertNotNull("the save must say it failed", repository.writeError)
        // The screen still shows the choice; only the persistence is missing.
        assertTrue(repository.settings().enabled)

        val healthy = repository()
        healthy.updateSettings { it.copy(enabled = true) }
        assertNull(healthy.writeError)
    }

    @Test
    fun `the summary tells the user what is missing in order`() {
        val none = WallSettings()
        assertTrue(none.summary(null).contains("Record a wall upgrade macro"))

        val noCost = none.copy(wallMacroId = 3L)
        assertTrue(noCost.summary("Walls").contains("Enter the cost per wall"))

        val off = noCost.copy(costPerWall = 1_000L)
        assertTrue(off.summary("Walls").contains("Off"))

        val untested = off.copy(enabled = true)
        assertTrue(untested.summary("Walls").contains("Run now"))

        val ready = untested.copy(testedWallMacroId = 3L, minReserve = 1_500_000L)
        val text = ready.summary("Walls")
        assertTrue(text, text.startsWith("Ready"))
        assertTrue(text, text.contains("1.5M in reserve"))

        val dangling = ready.copy(wallMacroId = 8L, testedWallMacroId = -1L)
        assertTrue(dangling.summary(null).contains("gone"))
    }

    @Test
    fun `amounts are compacted the same way on both surfaces`() {
        assertEquals("0", formatWallAmount(0L))
        assertEquals("999", formatWallAmount(999L))
        assertEquals("250K", formatWallAmount(250_000L))
        assertEquals("1.5M", formatWallAmount(1_500_000L))
        assertEquals("2M", formatWallAmount(2_000_000L))
    }
}