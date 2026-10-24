package com.clahbot.input

object TouchTransform {

    fun toScreen(
        rawX: Int,
        rawY: Int,
        device: TouchscreenDevice,
        width: Int,
        height: Int,
        rotation: Int
    ): ScreenPoint? {
        if (width <= 0 || height <= 0) return null
        if (device.maxX <= 0 || device.maxY <= 0) return null

        val nx = (rawX.toDouble() / (device.maxX + 1.0)).coerceIn(0.0, 1.0)
        val ny = (rawY.toDouble() / (device.maxY + 1.0)).coerceIn(0.0, 1.0)

        val (sx, sy) = when (rotation) {
            1 -> ny to 1.0 - nx
            2 -> 1.0 - nx to 1.0 - ny
            3 -> 1.0 - ny to nx
            else -> nx to ny
        }
        return ScreenPoint((sx * width).toFloat(), (sy * height).toFloat())
    }
}
