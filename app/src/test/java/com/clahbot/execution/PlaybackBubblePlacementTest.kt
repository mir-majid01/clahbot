package com.clahbot.execution

import com.clahbot.data.models.GesturePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBubblePlacementTest {

    private val width = 1224
    private val height = 2720
    private val bubble = 144f
    private val side = 24f
    private val top = 132f

    @Test
    fun noTouchesUsesTheTopRightCorner() {
        assertEquals(
            BubbleCorner.TOP_RIGHT,
            PlaybackBubblePlacement.chooseCorner(emptyList(), width, height, bubble, side, top)
        )
    }

    @Test
    fun touchNearTheTopLeftMovesTheBubbleToTheTopRight() {
        val points = listOf(GesturePoint(0.05f, 0.05f, 0))

        assertEquals(
            BubbleCorner.TOP_RIGHT,
            PlaybackBubblePlacement.chooseCorner(points, width, height, bubble, side, top)
        )
    }

    @Test
    fun touchNearTheTopRightMovesTheBubbleToTheTopLeft() {
        val points = listOf(GesturePoint(0.95f, 0.05f, 0))

        assertEquals(
            BubbleCorner.TOP_LEFT,
            PlaybackBubblePlacement.chooseCorner(points, width, height, bubble, side, top)
        )
    }

    @Test
    fun theCornerWithTheFewerTouchesWins() {
        val points = listOf(
            GesturePoint(0.02f, 0.06f, 0),
            GesturePoint(0.05f, 0.08f, 1),
            GesturePoint(0.90f, 0.60f, 2)
        )

        assertEquals(
            BubbleCorner.TOP_RIGHT,
            PlaybackBubblePlacement.chooseCorner(points, width, height, bubble, side, top)
        )
    }

    @Test
    fun invalidGeometryDefaultsToTheTopRight() {
        assertEquals(
            BubbleCorner.TOP_RIGHT,
            PlaybackBubblePlacement.chooseCorner(emptyList(), 0, height, bubble, side, top)
        )
    }
}
