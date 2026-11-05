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
}
