package com.clahbot.data

import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MacroRepositoryTest {

    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var repository: MacroRepository

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-test").toFile()
        file = File(directory, "macros.json")
        repository = MacroRepository(file)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun savesAndLoadsMacro() = runBlocking {
        val saved = repository.save(sampleMacro())
        assertNotNull(saved)

        val loaded = repository.load()
        assertEquals(1, loaded.size)
        assertEquals("Test macro", loaded[0].name)
        assertEquals(Macro.CURRENT_SCHEMA_VERSION, loaded[0].schemaVersion)
        assertEquals(2, loaded[0].gestures[0].points.size)
    }

    @Test
    fun rejectsMacroWithoutGestures() = runBlocking {
        assertNull(repository.save(sampleMacro().copy(gestures = emptyList())))
        assertEquals(0, repository.load().size)
    }

    @Test
    fun blankNameFallsBackToDefault() = runBlocking {
        val saved = repository.save(sampleMacro(name = "   "))
        assertEquals("Macro", saved?.name)
    }

    @Test
    fun createPreservesTheExactNameAndNeverReusesASuppliedId() = runBlocking {
        val existing = repository.save(sampleMacro(id = 1, name = "Existing"))!!
        val result = repository.create(sampleMacro(id = 1, name = "  Farm  LOOP  "))
        val created = (result as MacroWriteResult.Saved).macro

        assertTrue(created.id != existing.id)
        assertEquals("Farm  LOOP", created.name)
        assertEquals(Macro.KIND_ATTACK, created.kind)
        assertEquals("Existing", repository.get(existing.id)?.name)
        assertEquals(2, repository.load().size)
    }

    @Test
    fun createRejectsDuplicateNamesWithoutWriting() = runBlocking {
        repository.save(sampleMacro(id = 1, name = "One"))

        val result = repository.create(sampleMacro(id = 2, name = " one "))

        assertTrue(result is MacroWriteResult.Rejected)
        assertEquals(1, repository.load().size)
        assertNull(repository.get(2))
    }

    @Test
    fun createRejectsBlankAndOverlongNamesWithoutWriting() = runBlocking {
        assertTrue(repository.create(sampleMacro(name = "   ")) is MacroWriteResult.Rejected)
        assertTrue(
            repository.create(sampleMacro(name = "x".repeat(41))) is MacroWriteResult.Rejected
        )
        assertEquals(0, repository.load().size)
    }

    @Test
    fun createRejectsARecordingWithoutActions() = runBlocking {
        val result = repository.create(sampleMacro().copy(gestures = emptyList()))
        assertTrue(result is MacroWriteResult.Rejected)
        assertEquals(0, repository.load().size)
    }

    @Test
    fun concurrentCreatesAllocateUniqueIdsAndPersistEveryRecording() = runBlocking {
        val results = (1..8).map { index ->
            async { repository.create(sampleMacro(id = 1, name = "Macro $index")) }
        }.awaitAll()

        val saved = results.map {
            assertTrue(it is MacroWriteResult.Saved)
            (it as MacroWriteResult.Saved).macro
        }
        assertEquals(8, saved.map { it.id }.toSet().size)
        assertEquals(8, repository.load().size)
    }

    @Test
    fun zeroSpeedIsResetToDefault() = runBlocking {
        val saved = repository.save(sampleMacro(speed = 0f))
        assertEquals(1f, saved?.speedMultiplier ?: 0f, 0.0001f)
    }

    @Test
    fun renameUpdatesOnlyTheTargetMacro() = runBlocking {
        val first = repository.save(sampleMacro(id = 1, name = "One"))!!
        repository.save(sampleMacro(id = 2, name = "Two"))

        val renamed = repository.rename(first.id, "Renamed") as MacroWriteResult.Saved
        assertEquals("Renamed", renamed.macro.name)
        assertEquals(first.id, renamed.macro.id)
        assertEquals(first.gestures, renamed.macro.gestures)
        assertEquals("Two", repository.get(2)?.name)
    }

    @Test
    fun renameRejectsBlankName() = runBlocking {
        val saved = repository.save(sampleMacro())!!
        assertTrue(repository.rename(saved.id, "   ") is MacroWriteResult.Rejected)
        assertEquals("Test macro", repository.get(saved.id)?.name)
    }

    @Test
    fun renameRejectsDuplicateAndOverlongNames() = runBlocking {
        repository.save(sampleMacro(id = 1, name = "One"))
        val second = repository.save(sampleMacro(id = 2, name = "Two"))!!

        assertTrue(repository.rename(second.id, " ONE ") is MacroWriteResult.Rejected)
        assertTrue(
            repository.rename(second.id, "x".repeat(41)) is MacroWriteResult.Rejected
        )
        assertEquals("Two", repository.get(second.id)?.name)
    }

    @Test
    fun renameAllowsTheSameMacroToKeepItsName() = runBlocking {
        val saved = repository.save(sampleMacro(name = "Farm Loop"))!!
        val result = repository.rename(saved.id, " farm loop ")
        assertEquals("farm loop", (result as MacroWriteResult.Saved).macro.name)
    }

    @Test
    fun renameRejectsAMissingMacro() = runBlocking {
        assertTrue(repository.rename(999, "Ghost") is MacroWriteResult.Rejected)
        assertEquals(0, repository.load().size)
    }

    @Test
    fun settingsUpdatePreservesIdentityActionsAndStats() = runBlocking {
        val source = repository.save(sampleMacro(name = "Original"))!!
        val candidate = source.copy(
            name = "Renamed",
            gestures = emptyList(),
            createdAt = 999L,
            playCount = 99,
            speedMultiplier = 2f,
            repeatMode = "3"
        )

        val updated = (repository.updateSettings(candidate) as MacroWriteResult.Saved).macro

        assertEquals(source.id, updated.id)
        assertEquals(source.createdAt, updated.createdAt)
        assertEquals(source.gestures, updated.gestures)
        assertEquals(source.kind, updated.kind)
        assertEquals(source.playCount, updated.playCount)
        assertEquals("Renamed", updated.name)
        assertEquals(2f, updated.speedMultiplier)
        assertEquals("3", updated.repeatMode)
    }

    @Test
    fun settingsUpdateRejectsDuplicateNamesWithoutWriting() = runBlocking {
        repository.save(sampleMacro(id = 1, name = "One"))
        val second = repository.save(sampleMacro(id = 2, name = "Two"))!!

        assertTrue(
            repository.updateSettings(second.copy(name = " one ")) is MacroWriteResult.Rejected
        )
        assertEquals("Two", repository.get(second.id)?.name)
    }

    @Test
    fun deleteRemovesOnlyTheTargetMacro() = runBlocking {
        val first = repository.save(sampleMacro(id = 1, name = "One"))!!
        repository.save(sampleMacro(id = 2, name = "Two"))

        assertTrue(repository.delete(first.id))
        assertNull(repository.get(first.id))
        assertNotNull(repository.get(2))
    }

    @Test
    fun playbackSettingsArePersistedAndClamped() = runBlocking {
        val saved = repository.save(
            sampleMacro(speed = 12f, repeat = Macro.REPEAT_INFINITE)
        )!!
        assertEquals(4f, saved.speedMultiplier, 0.0001f)
        assertEquals(Macro.REPEAT_INFINITE, repository.get(saved.id)?.repeatMode)
    }

    @Test
    fun customRepeatAndStopConditionsAreClampedAndPersisted() = runBlocking {
        val saved = repository.save(
            sampleMacro(
                repeat = "10000",
                countdownMs = 90_000L,
                stopMinutes = 0,
                stopAttacks = 20_000
            )
        )!!
        assertEquals(Macro.REPEAT_ONCE, saved.repeatMode)
        assertEquals(Macro.MAX_START_COUNTDOWN_MS, saved.startCountdownMs)
        assertNull(saved.stopAfterMinutes)
        assertEquals(Macro.MAX_STOP_AFTER_ATTACKS, saved.stopAfterAttacks)

        val custom = repository.save(
            sampleMacro(id = 2, repeat = "7", stopMinutes = 15, stopAttacks = 4)
        )!!
        assertEquals("7", custom.repeatMode)
        assertEquals(15, custom.stopAfterMinutes)
        assertEquals(4, custom.stopAfterAttacks)
        assertEquals("7", repository.load().first { it.id == 2L }.repeatMode)
    }

    @Test
    fun pinnedMacrosSortFirstWithoutChangingRecencyOtherwise() = runBlocking {
        repository.save(sampleMacro(id = 1, name = "Old", createdAt = 1))
        repository.save(sampleMacro(id = 2, name = "New", createdAt = 2))
        assertEquals("New", repository.macros.value.first().name)

        repository.save(
            repository.get(1)!!.copy(pinned = true)
        )
        assertEquals("Old", repository.macros.value.first().name)
        assertEquals("New", repository.macros.value[1].name)
    }

    @Test
    fun duplicateGetsANewIdAndDoesNotCopyPlayStats() = runBlocking {
        val source = repository.save(sampleMacro(name = "Attack"))!!
        repository.markPlayed(source.id)
        val copy = repository.duplicate(source.id)!!

        assertTrue(copy.id != source.id)
        assertEquals("Attack (copy)", copy.name)
        assertEquals(0, copy.playCount)
        assertNull(copy.lastPlayedAt)
        assertEquals(source.gestures.size, copy.gestures.size)
        assertEquals(2, repository.load().size)

        val played = repository.markPlayed(source.id)!!
        assertEquals(2, played.playCount)
        assertNotNull(played.lastPlayedAt)
    }

    @Test
    fun oldMacroDefaultsForNewSettings() = runBlocking {
        repository.save(sampleMacro())
        val loaded = repository.load().first()
        assertEquals(0L, loaded.startCountdownMs)
        assertTrue(loaded.stopOnBackground)
        assertNull(loaded.stopAfterMinutes)
        assertNull(loaded.stopAfterAttacks)
        assertEquals(0, loaded.playCount)
        assertEquals(false, loaded.pinned)
    }

    @Test
    fun corruptFileIsQuarantinedAndTreatedAsEmpty() = runBlocking {
        file.writeText("{ this is not json")

        assertEquals(0, repository.load().size)
        val quarantined = directory.listFiles()?.any { it.name.contains("corrupt") } ?: false
        assertTrue(quarantined)
    }

    @Test
    fun unreadableFileIsQuarantinedNotSilentlyEmptied() = runBlocking {
        // A directory at the macro path exists but cannot be read as text: this is the
        // read-failure branch, distinct from the corrupt-JSON branch above. The original
        // must be preserved aside so a later read-modify-write cannot clobber irreplaceable
        // macros by treating an unreadable file as an empty library.
        assertTrue(file.mkdir())

        assertEquals(0, repository.load().size)
        assertFalse("the unreadable file was left in place to be overwritten", file.exists())
        val quarantined = directory.listFiles()?.any { it.name.contains("corrupt") } ?: false
        assertTrue("the unreadable file was not preserved", quarantined)
    }

    @Test
    fun legacyMacroWithoutPathIsUpgraded() = runBlocking {
        file.writeText(
            """
            {"schemaVersion":1,"macros":[{
              "id":7,"name":"Legacy","createdAt":1,"durationMs":100,
              "deviceWidth":1080,"deviceHeight":2400,"orientation":"portrait",
              "gamePackage":"com.supercell.clashofclans",
              "gestures":[{
                "id":1,"timestampMs":0,"gestureType":"swipe","durationMs":100,
                "normalizedStartX":0.1,"normalizedStartY":0.2,
                "normalizedEndX":0.8,"normalizedEndY":0.9,"pressure":0
              }],
              "repeatMode":"once","speedMultiplier":1.0,"randomOffset":"off"
            }]}
            """.trimIndent()
        )

        val loaded = repository.load()
        assertEquals(1, loaded.size)
        val gesture = loaded[0].gestures[0]
        assertEquals(2, gesture.points.size)
        assertEquals(0.1f, gesture.points.first().x, 0.0001f)
        assertEquals(0.9f, gesture.points.last().y, 0.0001f)
        assertEquals(Macro.CURRENT_SCHEMA_VERSION, loaded[0].schemaVersion)
        assertNull(loaded[0].repeatDelayMs)
        assertEquals(Macro.LEGACY_REPEAT_DELAY_MS, loaded[0].effectiveRepeatDelayMs)
    }

    @Test
    fun repeatDelayIsStoredExplicitlyAndClamped() = runBlocking {
        val zero = repository.save(sampleMacro(id = 1, repeatDelayMs = 0L))
        assertEquals(0L, zero?.repeatDelayMs)
        assertEquals(0L, zero?.effectiveRepeatDelayMs)

        val fiveSeconds = repository.save(sampleMacro(id = 2, repeatDelayMs = 5_000L))
        assertEquals(5_000L, fiveSeconds?.repeatDelayMs)
        assertEquals(5_000L, fiveSeconds?.effectiveRepeatDelayMs)

        val tooLong = repository.save(sampleMacro(id = 3, repeatDelayMs = 900_000L))
        assertEquals(Macro.MAX_REPEAT_DELAY_MS, tooLong?.repeatDelayMs)
        assertEquals(Macro.MAX_REPEAT_DELAY_MS, tooLong?.effectiveRepeatDelayMs)

        val negative = repository.save(sampleMacro(id = 4, repeatDelayMs = -5L))
        assertEquals(0L, negative?.repeatDelayMs)
        assertEquals(0L, negative?.effectiveRepeatDelayMs)

        val reloaded = repository.load().first { it.id == 2L }
        assertEquals(5_000L, reloaded.repeatDelayMs)
    }

    @Test
    fun savesAreAtomicAcrossRepeatedWrites() = runBlocking {
        repository.save(sampleMacro(id = 1, name = "One"))
        repository.save(sampleMacro(id = 2, name = "Two"))
        repository.save(sampleMacro(id = 1, name = "One updated"))

        val loaded = repository.load()
        assertEquals(2, loaded.size)
        assertEquals("One updated", loaded.first { it.id == 1L }.name)
    }

    @Test
    fun publishesMacroListOnEveryMutation() = runBlocking {
        assertEquals(0, repository.macros.value.size)

        repository.save(sampleMacro(id = 1, name = "One"))
        assertEquals(1, repository.macros.value.size)

        repository.save(sampleMacro(id = 2, name = "Two"))
        assertEquals(2, repository.macros.value.size)

        repository.rename(1, "Renamed")
        assertEquals("Renamed", repository.macros.value.first { it.id == 1L }.name)

        repository.delete(1)
        assertEquals(1, repository.macros.value.size)

        repository.clearAll()
        assertEquals(0, repository.macros.value.size)
    }

    @Test
    fun deleteManyRemovesOnlyTheSelectionAndBacksUpFirst() = runBlocking {
        repository.save(sampleMacro(id = 1, name = "One"))
        repository.save(sampleMacro(id = 2, name = "Two"))
        repository.save(sampleMacro(id = 3, name = "Three"))
        val backup = repository.backup("test")
        assertNotNull(backup)
        assertTrue(backup!!.isFile)
        assertTrue(backup.readText().contains("One"))
        assertTrue(backup.readText().contains("Three"))

        val removed = repository.deleteMany(setOf(1L, 3L))
        assertEquals(listOf("One", "Three"), removed.map { it.name }.sorted())
        assertEquals(listOf("Two"), repository.load().map { it.name })

        val restored = repository.restoreMany(removed)
        assertEquals(2, restored)
        assertEquals(
            listOf("One", "Three", "Two"),
            repository.load().map { it.name }.sorted()
        )
    }

    @Test
    fun deletingAnEmptySelectionNeverWritesTheLibrary() = runBlocking {
        repository.save(sampleMacro(id = 1, name = "One"))
        assertTrue(repository.deleteMany(emptySet()).isEmpty())
        assertEquals(1, repository.load().size)
    }

    private fun sampleMacro(
        id: Long = 1,
        name: String = "Test macro",
        speed: Float = 1f,
        repeatDelayMs: Long? = null,
        repeat: String = Macro.REPEAT_ONCE,
        createdAt: Long = 1_000L + id,
        countdownMs: Long = 0L,
        stopMinutes: Int? = null,
        stopAttacks: Int? = null
    ) = Macro(
        id = id,
        name = name,
        createdAt = createdAt,
        deviceWidth = 1080,
        deviceHeight = 2400,
        orientation = "portrait",
        speedMultiplier = speed,
        repeatDelayMs = repeatDelayMs,
        repeatMode = repeat,
        startCountdownMs = countdownMs,
        stopAfterMinutes = stopMinutes,
        stopAfterAttacks = stopAttacks,
        gestures = listOf(
            RecordedGesture(
                id = 1,
                timestampMs = 0,
                gestureType = "tap",
                durationMs = 50,
                normalizedStartX = 0.5f,
                normalizedStartY = 0.5f,
                normalizedEndX = 0.5f,
                normalizedEndY = 0.5f,
                points = listOf(
                    GesturePoint(0.5f, 0.5f, 0L),
                    GesturePoint(0.5f, 0.5f, 50L)
                )
            )
        )
    )
}
