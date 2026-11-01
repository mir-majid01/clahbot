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
    fun snapToEdgesOnlySnapsInsideTheThreshold() {
        val width = 132
        val height = 132
        val margin = 24
        val threshold = 72
        val left = 120
        val right = 2720 - margin - width
        val top = 24
        val bottom = 1177 - margin - height

        val nearLeft = OverlayPlacement.snapToEdges(
            left + 20, 500, width, height, landscape, margin, threshold
        )
        assertEquals(left, nearLeft.x)
        assertEquals(500, nearLeft.y)

        val awayFromLeft = OverlayPlacement.snapToEdges(
            left + 73, 500, width, height, landscape, margin, threshold
        )
        assertEquals(left + 73, awayFromLeft.x)

        val nearRightBottom = OverlayPlacement.snapToEdges(
            right - 10, bottom - 12, width, height, landscape, margin, threshold
        )
        assertEquals(right, nearRightBottom.x)
        assertEquals(bottom, nearRightBottom.y)

        val nearTop = OverlayPlacement.snapToEdges(
            700, top + 30, width, height, landscape, margin, threshold
        )
        assertEquals(top, nearTop.y)
        assertEquals(700, nearTop.x)

        val middle = OverlayPlacement.snapToEdges(
            700, 500, width, height, landscape, margin, threshold
        )
        assertEquals(700, middle.x)
        assertEquals(500, middle.y)
    }

    @Test
    fun menuDefaultSitsOnTheLeftBelowTheStatusBar() {
        val point = OverlayPlacement.menuDefault(landscape, 24, 96)
        assertEquals(120, point.x)
        assertEquals(96, point.y)
    }
}
