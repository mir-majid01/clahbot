package com.clahbot.service

import android.os.Looper
import com.clahbot.data.BotState
import com.clahbot.data.ControllerState
import com.clahbot.data.MacroRepository
import com.clahbot.data.SettingsStore
import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.walls.WallsRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingMenuServiceRecordingTest {

    private lateinit var directory: File
    private lateinit var repository: MacroRepository
    private lateinit var wallsRepository: WallsRepository
    private lateinit var service: FloatingMenuService

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("clahbot-controller").toFile()
        repository = MacroRepository(File(directory, "macros.json"))
        wallsRepository = WallsRepository(File(directory, "walls.json"))
        service = Robolectric.buildService(FloatingMenuService::class.java).get()
        val settings = SettingsStore.get(RuntimeEnvironment.getApplication())
        settings.update { it.copy(hapticFeedback = false) }
        ReflectionHelpers.setField(service, "repository", repository)
        ReflectionHelpers.setField(service, "wallsRepository", wallsRepository)
        ReflectionHelpers.setField(service, "settingsStore", settings)
        ReflectionHelpers.setField(service, "recorded", mutableListOf<RecordedGesture>())
        BotState.setState(ControllerState.READY)
        BotState.setStatus("before")
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun aFailedDeleteLeavesTheSavedMacroUntouched() {
        val saved = macro(id = 99L, name = "Ghost", kind = Macro.KIND_ATTACK)
        ReflectionHelpers.setField(service, "savedMacro", saved)

        ReflectionHelpers.callInstanceMethod<Any?>(service, "undoSavedMacro")
        await { BotState.status.value != "before" }

        assertNotNull(ReflectionHelpers.getField<Macro?>(service, "savedMacro"))
        assertTrue(ReflectionHelpers.getField<List<RecordedGesture>>(service, "recorded").isEmpty())
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "savePending"))
    }

    @Test
    fun undoingASavedWallMacroRestoresTheWallRecordingAndClearsItsSelection() = runBlocking {
        val saved = repository.save(macro(id = 1L, name = "Wall", kind = Macro.KIND_WALL))!!
        wallsRepository.updateSettings {
            it.copy(wallMacroId = saved.id, testedWallMacroId = saved.id)
        }
        ReflectionHelpers.setField(service, "savedMacro", saved)
        ReflectionHelpers.setField(service, "recordingWallMacro", false)

        ReflectionHelpers.callInstanceMethod<Any?>(service, "undoSavedMacro")
        await { ReflectionHelpers.getField<Macro?>(service, "savedMacro") == null }

        assertNull(repository.get(saved.id))
        assertEquals(-1L, wallsRepository.settings().wallMacroId)
        assertEquals(-1L, wallsRepository.settings().testedWallMacroId)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "recordingWallMacro"))
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "savePending"))
        assertEquals(saved.gestures, ReflectionHelpers.getField<List<RecordedGesture>>(service, "recorded"))
    }

    @Test
    fun undoingAWallMacroDoesNotClearADifferentSelectedWallMacro() = runBlocking {
        val saved = repository.save(macro(id = 1L, name = "Wall", kind = Macro.KIND_WALL))!!
        wallsRepository.updateSettings {
            it.copy(wallMacroId = 77L, testedWallMacroId = 77L)
        }
        ReflectionHelpers.setField(service, "savedMacro", saved)

        ReflectionHelpers.callInstanceMethod<Any?>(service, "undoSavedMacro")
        await { ReflectionHelpers.getField<Macro?>(service, "savedMacro") == null }

        assertEquals(77L, wallsRepository.settings().wallMacroId)
        assertEquals(77L, wallsRepository.settings().testedWallMacroId)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "recordingWallMacro"))
    }

    @Test
    fun discardingAReviewCancelsAnInFlightSave() {
        val waiter = CoroutineScope(Dispatchers.Unconfined).launch { delay(60_000) }
        ReflectionHelpers.setField(service, "saveJob", waiter)
        ReflectionHelpers.setField(service, "savePending", true)
        ReflectionHelpers.setField(service, "recordingWallMacro", true)
        ReflectionHelpers.getField<MutableList<RecordedGesture>>(service, "recorded").add(gesture())

        ReflectionHelpers.callInstanceMethod<Any?>(service, "discardReviewedRecording")

        assertTrue("the pending save was left running", waiter.isCancelled)
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "savePending"))
        assertTrue(ReflectionHelpers.getField<List<RecordedGesture>>(service, "recorded").isEmpty())
        waiter.cancel()
    }

    @Test
    fun undoingADiscardRestoresTheWallRecordingKind() {
        ReflectionHelpers.setField(service, "savePending", true)
        ReflectionHelpers.setField(service, "recordingWallMacro", true)
        ReflectionHelpers.getField<MutableList<RecordedGesture>>(service, "recorded").add(gesture())

        ReflectionHelpers.callInstanceMethod<Any?>(service, "discardReviewedRecording")
        ReflectionHelpers.callInstanceMethod<Any?>(service, "undoDiscardedRecording")

        assertTrue(ReflectionHelpers.getField<Boolean>(service, "recordingWallMacro"))
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "savePending"))
        assertEquals(1, ReflectionHelpers.getField<List<RecordedGesture>>(service, "recorded").size)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        assertTrue("controller state did not settle", condition())
    }

    private fun gesture() = RecordedGesture(
        gestureType = "tap",
        durationMs = 80L,
        points = listOf(GesturePoint(0.1f, 0.2f, 0L), GesturePoint(0.1f, 0.2f, 80L))
    )

    private fun macro(id: Long, name: String, kind: String) = Macro(
        id = id,
        name = name,
        kind = kind,
        gestures = listOf(gesture())
    )
}