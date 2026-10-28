package com.clahbot.data

import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MacroImportTest {

    private lateinit var directory: File
    private lateinit var repository: MacroRepository

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-import-test").toFile()
        repository = MacroRepository(File(directory, "macros.json"))
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun macro(name: String): Macro = Macro(
        id = 0L,
        name = name,
        gestures = listOf(
            RecordedGesture(
                id = 1,
                timestampMs = 0L,
                durationMs = 40L,
                points = listOf(
                    GesturePoint(0.1f, 0.1f, 0L),
                    GesturePoint(0.2f, 0.2f, 40L)
                )
            )
        ),
        repeatMode = Macro.REPEAT_INFINITE,
        speedMultiplier = 1.5f,
        repeatDelayMs = 3_000L
    )

    @Test
    fun importGetsANewIdAndANonClashingName() = runBlocking {
        val original = repository.save(macro("Attack").copy(id = 1L))
        assertNotNull(original)
        val imported = repository.importShared(macro("Attack"))
        assertNotNull(imported)
        assertEquals(2, repository.load().size)
        assertNotEquals(original!!.id, imported!!.id)
        assertEquals("Attack (2)", imported.name)
        assertEquals(1, imported.gestures.size)
        assertEquals(Macro.REPEAT_INFINITE, imported.repeatMode)
        assertEquals(3_000L, imported.repeatDelayMs)
        assertEquals(1.5f, imported.speedMultiplier, 0.0001f)
    }

    @Test
    fun importRejectsAnEmptyGestureMacro() = runBlocking {
        repository.save(macro("Attack").copy(id = 1L))
        assertEquals(null, repository.importShared(macro("Empty").copy(gestures = emptyList())))
        assertEquals(1, repository.load().size)
    }
}
