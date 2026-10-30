package com.clahbot.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementTest {

    private val portrait = OverlayBounds(0, 80, 1080, 2400)
    private val landscape = OverlayBounds(96, 0, 2720, 1177)

    @Test
    fun cornersStayInsideTheSafeArea() {
        val topRight = OverlayPlacement.defaultBubble(
            BubbleCorner.TOP_RIGHT, 132, 132, landscape, 24
        )
        assertEquals(2720 - 24 - 132, topRight.x)
        assertEquals(24, topRight.y)

        val bottomLeft = OverlayPlacement.defaultBubble(
            BubbleCorner.BOTTOM_LEFT, 132, 132, portrait, 24
        )
        assertEquals(24, bottomLeft.x)
        assertEquals(2400 - 24 - 132, bottomLeft.y)
    }

    @Test
    fun clampKeepsTheWholeTouchTargetVisible() {
        val clamped = OverlayPlacement.clamp(
            x = 2600,
            y = -40,
            width = 132,
            height = 132,
            bounds = landscape,
            margin = 24
        )
        assertEquals(2720 - 24 - 132, clamped.x)
        assertEquals(24, clamped.y)
    }

    @Test
    fun snapChoosesTheNearestSide() {
        assertEquals(120, OverlayPlacement.snapX(100, 132, landscape, 24))
        assertEquals(
            2720 - 24 - 132,
            OverlayPlacement.snapX(2200, 132, landscape, 24)
        )
    }

    @Test
    fun menuDefaultSitsOnTheLeftBelowTheStatusBar() {
        val point = OverlayPlacement.menuDefault(landscape, 24, 96)
        assertEquals(120, point.x)
        assertEquals(96, point.y)
    }
}
