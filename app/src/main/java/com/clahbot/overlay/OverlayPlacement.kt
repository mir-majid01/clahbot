package com.clahbot.overlay

import kotlin.math.abs

enum class BubbleCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

data class OverlayPoint(val x: Int, val y: Int)

data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

object OverlayPlacement {

    /** Keeps a window fully inside [bounds]; margins are applied on every side. */
    fun clamp(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        bounds: OverlayBounds,
        margin: Int
    ): OverlayPoint {
        val minX = bounds.left + margin
        val minY = bounds.top + margin
        val maxX = (bounds.right - margin - width).coerceAtLeast(minX)
        val maxY = (bounds.bottom - margin - height).coerceAtLeast(minY)
        return OverlayPoint(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
    }

    fun defaultBubble(
        corner: BubbleCorner,
        width: Int,
        height: Int,
        bounds: OverlayBounds,
        margin: Int
    ): OverlayPoint {
        val left = bounds.left + margin
        val right = (bounds.right - margin - width).coerceAtLeast(left)
        val top = bounds.top + margin
        val bottom = (bounds.bottom - margin - height).coerceAtLeast(top)
        val x = if (corner == BubbleCorner.TOP_LEFT || corner == BubbleCorner.BOTTOM_LEFT) {
            left
        } else {
            right
        }
        val y = if (corner == BubbleCorner.TOP_LEFT || corner == BubbleCorner.TOP_RIGHT) {
            top
        } else {
            bottom
        }
        return OverlayPoint(x, y)
    }

    /** Nearest horizontal edge for an icon snap. */
    fun snapX(x: Int, width: Int, bounds: OverlayBounds, margin: Int): Int {
        val left = bounds.left + margin
        val right = (bounds.right - margin - width).coerceAtLeast(left)
        val center = x + width / 2
        return if (center <= bounds.left + bounds.width / 2) left else right
    }

    /**
     * Snaps only the axes that are within [threshold] of a safe edge. A release
     * in the middle of the screen never jumps to a corner.
     */
    fun snapToEdges(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        bounds: OverlayBounds,
        margin: Int,
        threshold: Int
    ): OverlayPoint {
        val left = bounds.left + margin
        val right = (bounds.right - margin - width).coerceAtLeast(left)
        val top = bounds.top + margin
        val bottom = (bounds.bottom - margin - height).coerceAtLeast(top)
        val snappedX = when {
            abs(x - left) <= threshold -> left
            abs(x - right) <= threshold -> right
            else -> x
        }
        val snappedY = when {
            abs(y - top) <= threshold -> top
            abs(y - bottom) <= threshold -> bottom
            else -> y
        }
        return OverlayPoint(snappedX, snappedY)
    }

    fun menuDefault(bounds: OverlayBounds, margin: Int, topOffset: Int): OverlayPoint =
        OverlayPoint(bounds.left + margin, bounds.top + topOffset)
}
