package com.clahbot.execution

import com.clahbot.data.models.GesturePoint
import kotlin.math.hypot

enum class BubbleCorner { TOP_LEFT, TOP_RIGHT }

object PlaybackBubblePlacement {

    private data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    /**
     * Picks the top corner whose small bubble overlaps the fewest recorded
     * touches, then the one farther from the nearest touch. This keeps the
     * floating icon out of a macro's own gesture path.
     */
    fun chooseCorner(
        points: List<GesturePoint>,
        width: Int,
        height: Int,
        bubbleSizePx: Float,
        sideMarginPx: Float,
        topMarginPx: Float
    ): BubbleCorner {
        if (width <= 0 || height <= 0 || bubbleSizePx <= 0f) {
            return BubbleCorner.TOP_RIGHT
        }
        val leftRect = Rect(
            left = sideMarginPx,
            top = topMarginPx,
            right = sideMarginPx + bubbleSizePx,
            bottom = topMarginPx + bubbleSizePx
        )
        val rightStart = (width - sideMarginPx - bubbleSizePx)
            .coerceAtLeast(sideMarginPx)
        val rightRect = Rect(
            left = rightStart,
            top = topMarginPx,
            right = rightStart + bubbleSizePx,
            bottom = topMarginPx + bubbleSizePx
        )

        val left = score(points, width, height, leftRect)
        val right = score(points, width, height, rightRect)
        return when {
            left.first < right.first -> BubbleCorner.TOP_LEFT
            right.first < left.first -> BubbleCorner.TOP_RIGHT
            left.second > right.second -> BubbleCorner.TOP_LEFT
            right.second > left.second -> BubbleCorner.TOP_RIGHT
            else -> BubbleCorner.TOP_RIGHT
        }
    }

    private fun score(
        points: List<GesturePoint>,
        width: Int,
        height: Int,
        rect: Rect
    ): Pair<Int, Float> {
        var inside = 0
        var nearest = Float.MAX_VALUE
        for (point in points) {
            val x = point.x.coerceIn(0f, 1f) * width
            val y = point.y.coerceIn(0f, 1f) * height
            if (x in rect.left..rect.right && y in rect.top..rect.bottom) {
                inside++
            }
            val dx = when {
                x < rect.left -> rect.left - x
                x > rect.right -> x - rect.right
                else -> 0f
            }
            val dy = when {
                y < rect.top -> rect.top - y
                y > rect.bottom -> y - rect.bottom
                else -> 0f
            }
            val distance = hypot(dx, dy)
            if (distance < nearest) nearest = distance
        }
        return inside to nearest
    }
}
