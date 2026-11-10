package com.clahbot.triggers

import com.clahbot.game.GameState
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerEngineTest {

    private fun frame(w: Int, h: Int, draw: (x: Int, y: Int) -> Int): TemplateMatcher.Frame {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = draw(x, y).coerceIn(0, 255)
            px[y * w + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        return TemplateMatcher.Frame(px, w, h)
    }

    private fun pattern(x: Int, y: Int) = 20 + x * 9 + y * 5

    private fun template(size: Int = 10) = frame(size, size) { x, y -> pattern(x, y) }

    private fun liveWithCopy(ox: Int = 40, oy: Int = 30, size: Int = 10): TemplateMatcher.Frame =
        frame(100, 80) { x, y ->
            if (x in ox until ox + size && y in oy until oy + size) pattern(x - ox, y - oy) else 10
        }

    private fun observation(forbidden: List<ScreenBox> = emptyList()) = ScreenObservation(
        state = GameState.HOME,
        confidence = 0.9f,
        forbidden = forbidden,
        frameWidth = 100,
        frameHeight = 80
    )

    private fun trigger(
        id: String = "t",
        action: TriggerAction,
        priority: Int = 0,
        enabled: Boolean = true,
        mode: PlaybackMode = PlaybackMode.DURING_AUTOMATION,
        threshold: Float = 0.85f
    ) = ImageTrigger(
        id = id,
        name = id,
        imageFile = "$id.png",
        refWidth = 10,
        refHeight = 10,
        threshold = threshold,
        action = action,
        enabled = enabled,
        priority = priority,
        playbackMode = mode
    )

    private val anyTemplate: (ImageTrigger) -> TemplateMatcher.Frame? = { template() }

    private fun tap(x: Float, y: Float, relative: Boolean = true) =
        TriggerAction(type = TriggerActionType.TAP, point = TriggerPoint(x, y, relative))

    @Test
    fun `a matched tap is translated into the matched rectangle`() {
        val decision = TriggerEngine.decide(
            liveWithCopy(),
            anyTemplate,
            observation(),
            listOf(trigger(action = tap(0.5f, 0.5f))),
            playerRunning = false
        )
        assertTrue(decision is TriggerEngine.Decision.Act)
        val act = decision as TriggerEngine.Decision.Act
        val start = act.start!!
        assertTrue("x ${start.x} near 45", abs(start.x - 45f) <= 2f)
        assertTrue("y ${start.y} near 35", abs(start.y - 35f) <= 2f)
    }

    @Test
    fun `a tap on a never-press box is refused`() {
        val gem = ScreenBox(35, 25, 55, 45, kind = "caption:buy")
        val decision = TriggerEngine.decide(
            liveWithCopy(),
            anyTemplate,
            observation(forbidden = listOf(gem)),
            listOf(trigger(action = tap(0.5f, 0.5f))),
            playerRunning = false
        )
        assertTrue(decision is TriggerEngine.Decision.Refused)
    }

    @Test
    fun `run macro is refused while a macro is already running`() {
        val action = TriggerAction(type = TriggerActionType.RUN_MACRO, macroId = 7L)
        val decision = TriggerEngine.decide(
            liveWithCopy(), anyTemplate, observation(),
            listOf(trigger(action = action)), playerRunning = true
        )
        assertTrue(decision is TriggerEngine.Decision.Refused)
    }

    @Test
    fun `run macro is allowed when idle`() {
        val action = TriggerAction(type = TriggerActionType.RUN_MACRO, macroId = 7L)
        val decision = TriggerEngine.decide(
            liveWithCopy(), anyTemplate, observation(),
            listOf(trigger(action = action)), playerRunning = false
        )
        assertTrue(decision is TriggerEngine.Decision.Act)
    }

    @Test
    fun `an idle-only trigger is skipped during a run`() {
        val decision = TriggerEngine.decide(
            liveWithCopy(), anyTemplate, observation(),
            listOf(trigger(action = tap(0.5f, 0.5f), mode = PlaybackMode.IDLE_ONLY)),
            playerRunning = true
        )
        assertTrue(decision is TriggerEngine.Decision.None)
    }

    @Test
    fun `a disabled trigger never fires`() {
        val decision = TriggerEngine.decide(
            liveWithCopy(), anyTemplate, observation(),
            listOf(trigger(action = tap(0.5f, 0.5f), enabled = false)),
            playerRunning = false
        )
        assertTrue(decision is TriggerEngine.Decision.None)
    }

    @Test
    fun `the higher priority trigger wins`() {
        val low = trigger(id = "low", action = TriggerAction(type = TriggerActionType.WAIT), priority = 1)
        val high = trigger(id = "high", action = TriggerAction(type = TriggerActionType.STOP), priority = 5)
        val decision = TriggerEngine.decide(
            liveWithCopy(), anyTemplate, observation(), listOf(low, high), playerRunning = false
        )
        assertTrue(decision is TriggerEngine.Decision.Act)
        assertEquals("high", (decision as TriggerEngine.Decision.Act).trigger.id)
    }

    @Test
    fun `an unmatched screen fires nothing`() {
        val blank = frame(100, 80) { _, _ -> 10 }
        val decision = TriggerEngine.decide(
            blank, anyTemplate, observation(),
            listOf(trigger(action = tap(0.5f, 0.5f))), playerRunning = false
        )
        assertTrue(decision is TriggerEngine.Decision.None)
    }
}
