package com.clahbot.walls

import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.game.GameState
import com.clahbot.game.PopupKind
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import com.clahbot.game.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallLogicTest {

    // ---------- reserve and affordability ----------

    @Test
    fun `the example from the spec spends exactly what is above the reserve`() {
        assertEquals(
            1_000_000L,
            WallLogic.spendable(balance = 1_500_000L, reserve = 500_000L)
        )
        assertTrue(WallLogic.canAfford(1_500_000L, 500_000L, cost = 1_000_000L))
    }

    @Test
    fun `a missing counter has nothing to spend`() {
        assertEquals(0L, WallLogic.spendable(null, 0L))
        assertFalse(WallLogic.canAfford(null, 0L, 1_000_000L))
    }

    @Test
    fun `reserve larger than the balance never spends`() {
        assertEquals(0L, WallLogic.spendable(300_000L, 500_000L))
        assertFalse(WallLogic.canAfford(300_000L, 500_000L, 1L))
    }

    @Test
    fun `zero cost never triggers`() {
        assertFalse(WallLogic.canAfford(9_999_999L, 0L, 0L))
        assertEquals(
            WallTrigger.NONE,
            WallLogic.choose(9_999_999L, 9_999_999L, cost = 0L, reserve = 0L,
                mode = WallResourceMode.BOTH, priority = WallPriority.GOLD_FIRST)
        )
    }

    // ---------- resource mode ----------

    @Test
    fun `gold mode ignores a richer elixir stockpile`() {
        assertEquals(
            WallTrigger.NONE,
            WallLogic.choose(
                gold = 100L,
                elixir = 99_000_000L,
                cost = 1_000L,
                reserve = 0L,
                mode = WallResourceMode.GOLD,
                priority = WallPriority.GOLD_FIRST
            )
        )
    }

    @Test
    fun `elixir mode only ever picks elixir`() {
        assertEquals(
            WallTrigger.ELIXIR,
            WallLogic.choose(
                gold = 99_000_000L,
                elixir = 5_000L,
                cost = 1_000L,
                reserve = 0L,
                mode = WallResourceMode.ELIXIR,
                priority = WallPriority.GOLD_FIRST
            )
        )
    }

    @Test
    fun `both mode follows the configured priority`() {
        val both = WallResourceMode.BOTH
        assertEquals(
            WallTrigger.GOLD,
            WallLogic.choose(5_000L, 5_000L, 1_000L, 0L, both, WallPriority.GOLD_FIRST)
        )
        assertEquals(
            WallTrigger.ELIXIR,
            WallLogic.choose(5_000L, 5_000L, 1_000L, 0L, both, WallPriority.ELIXIR_FIRST)
        )
    }

    @Test
    fun `both mode uses whichever resource alone can pay`() {
        assertEquals(
            WallTrigger.GOLD,
            WallLogic.choose(5_000L, 100L, 1_000L, 0L, WallResourceMode.BOTH, WallPriority.ELIXIR_FIRST)
        )
        assertEquals(
            WallTrigger.ELIXIR,
            WallLogic.choose(100L, 5_000L, 1_000L, 0L, WallResourceMode.BOTH, WallPriority.GOLD_FIRST)
        )
        assertEquals(
            WallTrigger.NONE,
            WallLogic.choose(100L, 100L, 1_000L, 0L, WallResourceMode.BOTH, WallPriority.GOLD_FIRST)
        )
    }

    @Test
    fun `the reserve is applied per resource in both mode`() {
        assertEquals(
            WallTrigger.NONE,
            WallLogic.choose(
                gold = 2_000L,
                elixir = 2_000L,
                cost = 1_500L,
                reserve = 1_000L,
                mode = WallResourceMode.BOTH,
                priority = WallPriority.GOLD_FIRST
            )
        )
    }

    // ---------- reading stability ----------

    @Test
    fun `two equal reads are stable`() {
        assertTrue(WallLogic.stable(1_543_000L, 1_543_000L))
    }

    @Test
    fun `collector income inside one percent stays stable`() {
        assertTrue(WallLogic.stable(1_000_000L, 1_005_000L))
        assertTrue(WallLogic.stable(2_000L, 3_000L))
    }

    @Test
    fun `a large jump or a falling counter is not stable`() {
        assertFalse(WallLogic.stable(1_000_000L, 1_200_000L))
        assertFalse(WallLogic.stable(1_000_000L, 900_000L))
        assertFalse(WallLogic.stable(null, 1_000L))
        assertFalse(WallLogic.stable(1_000L, null))
    }

    @Test
    fun `both mode is stable when either resource is stable`() {
        assertTrue(
            WallLogic.stableReadings(
                goldFirst = 1_000_000L,
                goldSecond = 1_900_000L,
                elixirFirst = 1_000_000L,
                elixirSecond = 1_000_000L,
                mode = WallResourceMode.BOTH
            )
        )
        assertFalse(
            WallLogic.stableReadings(
                goldFirst = 1_000_000L,
                goldSecond = 1_900_000L,
                elixirFirst = 900_000L,
                elixirSecond = 1_900_000L,
                mode = WallResourceMode.BOTH
            )
        )
    }

    // ---------- gem safety ----------

    private fun observation(
        forbidden: List<ScreenBox>,
        state: GameState = GameState.HOME
    ) = ScreenObservation(
        state = state,
        confidence = 0.9f,
        popupKind = if (state == GameState.POPUP) PopupKind.GEM_SPEND else null,
        forbidden = forbidden,
        lines = listOf(TextLine("buy", 0.72f, 0.62f, 0.78f, 0.68f)),
        frameWidth = 1000,
        frameHeight = 500
    )

    private fun macroWith(vararg taps: Pair<Float, Float>): Macro = Macro(
        id = 1L,
        name = "Walls",
        kind = Macro.KIND_WALL,
        gestures = taps.mapIndexed { index, (x, y) ->
            RecordedGesture(
                id = index,
                timestampMs = index * 500L,
                gestureType = "tap",
                normalizedStartX = x,
                normalizedStartY = y,
                points = listOf(GesturePoint(x, y, 0L))
            )
        }
    )

    @Test
    fun `a recorded tap on a gem button is reported before the macro runs`() {
        val gemButton = ScreenBox(720, 310, 780, 340)
        val macro = macroWith(0.5f to 0.3f, 0.75f to 0.65f)
        val reasons = WallLogic.unsafeTaps(macro, observation(listOf(gemButton)))
        assertEquals(1, reasons.size)
        assertTrue(reasons.first().contains("never-press"))
    }

    @Test
    fun `taps away from the forbidden boxes are allowed`() {
        val gemButton = ScreenBox(720, 310, 780, 340)
        val macro = macroWith(0.5f to 0.3f, 0.2f to 0.8f)
        assertTrue(WallLogic.unsafeTaps(macro, observation(listOf(gemButton))).isEmpty())
    }

    @Test
    fun `stored paths cannot bypass the never press guard`() {
        val safeStart = macroWith(0.2f to 0.2f)
        val gesture = safeStart.gestures.single().copy(
            paths = listOf(com.clahbot.data.models.GesturePath(
                points = listOf(GesturePoint(0.75f, 0.65f, 0L))
            ))
        )
        val macro = safeStart.copy(gestures = listOf(gesture))
        assertTrue(WallLogic.unsafeTaps(macro, observation(listOf(ScreenBox(720, 310, 780, 340)))).isNotEmpty())
    }

    @Test
    fun `long presses on a forbidden control are refused`() {
        val macro = macroWith(0.75f to 0.65f).let {
            it.copy(gestures = listOf(it.gestures.single().copy(gestureType = "long_press")))
        }
        assertTrue(WallLogic.unsafeTaps(macro, observation(listOf(ScreenBox(720, 310, 780, 340)))).isNotEmpty())
    }

    @Test
    fun `swipes crossing a forbidden control are refused`() {
        val macro = macroWith(0.2f to 0.65f).let {
            it.copy(gestures = listOf(it.gestures.single().copy(
                gestureType = "swipe", normalizedEndX = 0.9f, normalizedEndY = 0.65f,
                points = listOf(GesturePoint(0.2f, 0.65f), GesturePoint(0.9f, 0.65f, 500))
            )))
        }
        assertTrue(WallLogic.unsafeTaps(macro, observation(listOf(ScreenBox(720, 310, 780, 340)))).isNotEmpty())
    }

    @Test
    fun `no frame geometry means the macro cannot be cleared to run`() {
        val macro = macroWith(0.5f to 0.5f)
        val noGeometry = observation(emptyList()).copy(frameWidth = 0, frameHeight = 0)
        assertTrue(WallLogic.unsafeTaps(macro, noGeometry).isNotEmpty())
    }

    // ---------- screening ----------

    @Test
    fun `only the home village may start a wall run`() {
        assertTrue(WallLogic.safeToStart(GameState.HOME))
        listOf(
            GameState.BATTLE,
            GameState.RESULTS,
            GameState.SEARCHING,
            GameState.LOADING,
            GameState.UNKNOWN,
            GameState.ATTACK_MENU,
            GameState.POPUP,
            GameState.NOT_CLASH,
            null
        ).forEach { assertFalse("start on $it", WallLogic.safeToStart(it)) }
    }

    @Test
    fun `popups, unknown screens and other apps stop the run`() {
        listOf(
            GameState.POPUP,
            GameState.UNKNOWN,
            GameState.NOT_CLASH,
            GameState.BATTLE
        ).forEach { assertTrue(it.name, WallLogic.unsafeDuringRun(it)) }
        listOf(
            GameState.HOME,
            GameState.RESULTS,
            GameState.LOADING,
            GameState.ATTACK_MENU,
            null
        ).forEach { assertFalse("${it}", WallLogic.unsafeDuringRun(it)) }
    }

    @Test
    fun `only a known screen counts as safe after the run`() {
        assertTrue(WallLogic.safeAfterRun(GameState.HOME))
        assertTrue(WallLogic.safeAfterRun(GameState.ATTACK_MENU))
        assertFalse(WallLogic.safeAfterRun(GameState.UNKNOWN))
        assertFalse(WallLogic.safeAfterRun(GameState.POPUP))
        assertFalse(WallLogic.safeAfterRun(GameState.LOADING))
    }

    @Test
    fun `a second upgrade in the same visit needs permission and proof`() {
        assertTrue(
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = true, verified = true, repeats = 0, maxRepeats = 5
            )
        )
        assertFalse(
            "the switch is off" ,
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = false, verified = true, repeats = 0, maxRepeats = 5
            )
        )
        assertFalse(
            "no balance drop proved the first one",
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = true, verified = false, repeats = 0, maxRepeats = 5
            )
        )
        assertFalse(
            "the last run did not spend",
            WallLogic.mayRepeatUpgrade(
                status = "stopped", allowed = true, verified = true, repeats = 0, maxRepeats = 5
            )
        )
    }

    @Test
    fun `repeats stop at the ceiling`() {
        assertTrue(
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = true, verified = true, repeats = 4, maxRepeats = 5
            )
        )
        assertFalse(
            WallLogic.mayRepeatUpgrade(
                status = "ran", allowed = true, verified = true, repeats = 5, maxRepeats = 5
            )
        )
    }

    // ---------- refusal diagnostics (the drag reported on 2026-11-08) ----------

    /** The refused wall swipe: right-edge drag (0.907,0.598) -> (0.936,0.271). */
    private fun reportedDrag(): Macro = macroWith(0.907f to 0.598f).let { macro ->
        macro.copy(
            gestures = listOf(
                macro.gestures.single().copy(
                    timestampMs = 3372L,
                    gestureType = "swipe",
                    normalizedEndX = 0.936f,
                    normalizedEndY = 0.271f,
                    points = listOf(
                        GesturePoint(0.907f, 0.598f),
                        GesturePoint(0.936f, 0.271f, 900L)
                    )
                )
            )
        )
    }

    @Test
    fun `the reported drag is still refused when a control sits on it`() {
        // 1000x500 frame: the drag runs (907,299) -> (936,135); this box is on it.
        val gem = ScreenBox(900, 200, 945, 240, kind = "gem")
        assertTrue(WallLogic.unsafeTaps(reportedDrag(), observation(listOf(gem))).isNotEmpty())
    }

    @Test
    fun `the same drag is allowed when nothing forbidden is on it`() {
        // Near the right edge but well below the path: scenery, not a control.
        val scenery = ScreenBox(880, 400, 960, 460, kind = "ring")
        assertTrue(WallLogic.unsafeTaps(reportedDrag(), observation(listOf(scenery))).isEmpty())
    }

    @Test
    fun `a box at the drag start is refused`() {
        val start = ScreenBox(900, 290, 915, 310, kind = "gem")
        assertTrue(WallLogic.unsafeTaps(reportedDrag(), observation(listOf(start))).isNotEmpty())
    }

    @Test
    fun `a refusal names the box and the frame it came from`() {
        val gem = ScreenBox(900, 200, 945, 240, kind = "gem")
        val observed = observation(listOf(gem))
        val hit = WallLogic.firstForbiddenHit(reportedDrag(), observed)
        assertEquals("gem", hit?.kind)
        assertEquals(gem, hit?.box)
        val detail = WallLogic.describeForbidden(observed, hit)
        assertTrue(detail, detail.contains("state=HOME"))
        assertTrue(detail, detail.contains("frame=1000x500"))
        assertTrue(detail, detail.contains("hit=gem@900,200-945,240"))
        assertTrue(detail, detail.contains("gesture=3372ms"))
    }

    @Test
    fun `caption boxes keep the word that refused the gesture`() {
        val caption = ScreenBox(900, 200, 945, 240, kind = "caption:gems")
        val observed = observation(listOf(caption))
        val hit = WallLogic.firstForbiddenHit(reportedDrag(), observed)
        assertEquals("caption:gems", hit?.kind)
        assertTrue(WallLogic.describeForbidden(observed, hit).contains("caption:gems"))
    }
}
