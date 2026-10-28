package com.clahbot.data

import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroShareTest {

    private fun macro(name: String = "Attack"): Macro = Macro(
        id = 7L,
        name = name,
        createdAt = 1_000L,
        durationMs = 80L,
        deviceWidth = 1080,
        deviceHeight = 2400,
        orientation = "landscape",
        gestures = listOf(
            RecordedGesture(
                id = 1,
                timestampMs = 10L,
                gestureType = "tap",
                durationMs = 80L,
                normalizedStartX = 0.5f,
                normalizedStartY = 0.5f,
                normalizedEndX = 0.5f,
                normalizedEndY = 0.5f,
                points = listOf(
                    GesturePoint(0.5f, 0.5f, 0L),
                    GesturePoint(0.51f, 0.52f, 80L)
                )
            )
        ),
        repeatMode = Macro.REPEAT_INFINITE,
        speedMultiplier = 1.5f,
        repeatDelayMs = 3_000L
    )

    @Test
    fun envelopeRoundTripKeepsPlaybackSettingsAndGestures() {
        val json = MacroShare.encode(macro(), "2.4.9")
        assertTrue(json.contains("\"format\":\"${MacroShare.FORMAT}\""))
        assertTrue(json.contains("\"formatVersion\":${MacroShare.FORMAT_VERSION}"))
        val decoded = MacroShare.decode(json)
        assertNotNull(decoded)
        assertEquals(0L, decoded!!.id)
        assertEquals("Attack", decoded.name)
        assertEquals("landscape", decoded.orientation)
        assertEquals(Macro.REPEAT_INFINITE, decoded.repeatMode)
        assertEquals(1.5f, decoded.speedMultiplier, 0.0001f)
        assertEquals(3_000L, decoded.repeatDelayMs)
        assertEquals(1, decoded.gestures.size)
        assertEquals(2, decoded.gestures[0].points.size)
        assertEquals(90L, decoded.durationMs)
    }

    @Test
    fun aBareMacroJsonIsAccepted() {
        val decoded = MacroShare.decode(Gson().toJson(macro("Bare")))
        assertNotNull(decoded)
        assertEquals("Bare", decoded!!.name)
    }

    @Test
    fun nonMacroAndEmptyGestureFilesAreRejected() {
        assertNull(MacroShare.decode(""))
        assertNull(MacroShare.decode("{}"))
        assertNull(MacroShare.decode("""{"format":"something-else"}"""))
        assertNull(MacroShare.decode(Gson().toJson(macro().copy(gestures = emptyList()))))
        assertNull(MacroShare.decode("""{"format":{},"formatVersion":1}"""))
        assertNull(MacroShare.decode("""{"format":"autoclash-macro","formatVersion":"x"}"""))
        assertFalse(MacroShare.decode("not json") != null)
    }

    @Test
    fun importRejectsAFutureEnvelopeVersion() {
        val json = MacroShare.encode(macro(), "2.4.9")
            .replace("\"formatVersion\":1", "\"formatVersion\":99")
        assertNull(MacroShare.decode(json))
    }
}
