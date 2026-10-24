package com.clahbot.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchTransformTest {

    private val touchscreen = TouchscreenDevice(
        path = "/dev/input/event2",
        name = "mtk-tpd",
        maxX = 19583,
        maxY = 43519,
        maxSlots = 9,
        direct = true
    )

    @Test
    fun rotationZeroUsesRawAxesAsPortrait() {
        val point = requireNotNull(
            TouchTransform.toScreen(12100, 4669, touchscreen, 1080, 2400, 0)
        )

        assertEquals(667.28f, point.x, 0.1f)
        assertEquals(257.48f, point.y, 0.1f)
    }

    @Test
    fun rotationOneTurnsRawAxesIntoLandscape() {
        val point = requireNotNull(
            TouchTransform.toScreen(12100, 4669, touchscreen, 2400, 1080, 1)
        )

        assertEquals(257.48f, point.x, 0.1f)
        assertEquals(412.72f, point.y, 0.1f)
    }

    @Test
    fun rotationTwoFlipsBothAxes() {
        val point = requireNotNull(
            TouchTransform.toScreen(12100, 4669, touchscreen, 1080, 2400, 2)
        )

        assertEquals(412.72f, point.x, 0.1f)
        assertEquals(2142.52f, point.y, 0.1f)
    }

    @Test
    fun rotationThreeFlipsAndSwapsAxes() {
        val point = requireNotNull(
            TouchTransform.toScreen(12100, 4669, touchscreen, 2400, 1080, 3)
        )

        assertEquals(2142.52f, point.x, 0.1f)
        assertEquals(667.28f, point.y, 0.1f)
    }

    @Test
    fun rawMaximumScalesWithInclusiveDenominator() {
        val point = requireNotNull(
            TouchTransform.toScreen(19583, 43519, touchscreen, 1080, 2400, 0)
        )

        assertEquals(1079.945f, point.x, 0.05f)
        assertEquals(2399.945f, point.y, 0.05f)
    }

    @Test
    fun outOfRangeRawValuesAreClampedToTheScreen() {
        val point = requireNotNull(
            TouchTransform.toScreen(999_999, -50, touchscreen, 1080, 2400, 0)
        )

        assertEquals(1080f, point.x, 0.001f)
        assertEquals(0f, point.y, 0.001f)
    }

    @Test
    fun invalidDisplayOrDeviceReturnsNull() {
        assertNull(TouchTransform.toScreen(100, 100, touchscreen, 0, 2400, 0))
        assertNull(TouchTransform.toScreen(100, 100, touchscreen, 1080, -1, 0))
        assertNull(
            TouchTransform.toScreen(
                100,
                100,
                touchscreen.copy(maxX = 0),
                1080,
                2400,
                0
            )
        )
    }
}
