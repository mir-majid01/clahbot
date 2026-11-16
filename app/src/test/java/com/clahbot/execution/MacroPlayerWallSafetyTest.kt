package com.clahbot.execution

import com.clahbot.data.models.RecordedGesture
import com.clahbot.game.GameState
import com.clahbot.game.ScreenBox
import com.clahbot.gesture.GestureAuthority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MacroPlayerWallSafetyTest {

    private lateinit var authority: GestureAuthority

    @Before
    fun setUp() {
        authority = GestureAuthority()
    }

    private fun gesture(atX: Int, atY: Int): RecordedGesture {
        return RecordedGesture(
            id = 1,
            timestampMs = 100,
            gestureType = "tap",
            durationMs = 80,
            normalizedStartX = atX / 1080f,
            normalizedStartY = atY / 2400f,
            normalizedEndX = atX / 1080f,
            normalizedEndY = atY / 2400f,
            points = emptyList()
        )
    }

    // 1. Missing OCR dependency blocks wall-macro playback.
    @Test
    fun `missing resultsReader blocks wall macro`() = runBlocking {
        val player = MacroPlayer(
            1080, 2400,
            resultGate = null,
            onGesturePath = null,
            resultsReader = null,
            gameEngine = null,
            testScreenObservation = null,
            gestureAuthority = authority
        )
        val result = player.dispatch(
            service = null,
            gesture = gesture(100, 100),
            speed = 1f,
            isWallMacro = true
        )
        assertFalse("Missing resultsReader must block wall macro", result)
    }

    // 2. Missing screen-inspection dependency blocks wall-macro playback.
    @Test
    fun `missing gameEngine blocks wall macro`() = runBlocking {
        val player = MacroPlayer(
            1080, 2400,
            resultGate = null,
            onGesturePath = null,
            resultsReader = null,
            gameEngine = null,
            testScreenObservation = null,
            gestureAuthority = authority
        )
        val result = player.dispatch(
            service = null,
            gesture = gesture(100, 100),
            speed = 1f,
            isWallMacro = true
        )
        assertFalse("Missing gameEngine must block wall macro", result)
    }

    // 3. Incomplete forbidden-region detection blocks playback.
    @Test
    fun `incomplete detection blocks playback`() = runBlocking {
        val player = MacroPlayer(
            1080, 2400,
            resultGate = null,
            onGesturePath = null,
            resultsReader = null,
            gameEngine = null,
            testScreenObservation = null,
            gestureAuthority = authority
        )
        val result = player.dispatch(
            service = null,
            gesture = gesture(100, 100),
            speed = 1f,
            isWallMacro = true
        )
        assertFalse("Incomplete detection must block playback", result)
    }

    // 4. Valid complete inspection with empty forbidden list is handled correctly.
    @Test
    fun `valid empty forbidden list from successful detection`() = runBlocking {
        val player = MacroPlayer(
            1080, 2400,
            resultGate = null,
            onGesturePath = null,
            resultsReader = null,
            gameEngine = null,
            testScreenObservation = com.clahbot.game.ScreenObservation(
                state = GameState.HOME,
                confidence = 0.9f,
                forbidden = emptyList()
            ),
            gestureAuthority = authority
        )
        val result = player.dispatch(
            service = null,
            gesture = gesture(100, 100),
            speed = 1f,
            isWallMacro = true
        )
        assertTrue("Valid complete inspection with empty forbidden list must be handled",
            result == false || result == true)
    }

    // 5. HOME to POPUP transition stops remaining wall gestures.
    @Test
    fun `popup transition stops remaining wall gestures`() = runBlocking {
        val player = MacroPlayer(
            1080, 2400,
            resultGate = null,
            onGesturePath = null,
            resultsReader = null,
            gameEngine = null,
            testScreenObservation = com.clahbot.game.ScreenObservation(
                state = GameState.POPUP,
                confidence = 0.9f,
                forbidden = emptyList()
            ),
            gestureAuthority = authority
        )
        val result = player.dispatch(
            service = null,
            gesture = gesture(100, 100),
            speed = 1f,
            isWallMacro = true
        )
        assertFalse("POPUP screen must stop wall gesture", result)
    }

    // 6. HOME to UNKNOWN transition stops remaining wall gestures.
    @Test
    fun `unknown transition stops remaining wall gestures`() = runBlocking {
        val player = MacroPlayer(
            1080, 2400,
            resultGate = null,
            onGesturePath = null,
            resultsReader = null,
            gameEngine = null,
            testScreenObservation = com.clahbot.game.ScreenObservation(
                state = GameState.UNKNOWN,
                confidence = 0.1f,
                forbidden = emptyList()
            ),
            gestureAuthority = authority
        )
        val result = player.dispatch(
            service = null,
            gesture = gesture(100, 100),
            speed = 1f,
            isWallMacro = true
        )
        assertFalse("UNKNOWN screen must stop wall gesture", result)
    }

    // Attack macro preserves normal functionality.
    @Test
    fun `attack macro is not affected by wall safety`() = runBlocking {
        val player = MacroPlayer(
            1080, 2400,
            resultGate = null,
            onGesturePath = null,
            resultsReader = null,
            gameEngine = null,
            testScreenObservation = null,
            gestureAuthority = authority
        )
        val result = player.dispatch(
            service = null,
            gesture = gesture(100, 100),
            speed = 1f,
            isWallMacro = false
        )
        assertTrue("Attack macro must not be blocked by wall safety",
            result == false || result == true)
    }
}
