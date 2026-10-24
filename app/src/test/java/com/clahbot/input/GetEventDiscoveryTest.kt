package com.clahbot.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GetEventDiscoveryTest {

    private val realOutput = """
        add device 1: /dev/input/event2
          name:     "mtk-tpd"
          events:
            KEY (0001): KEY_W                 KEY_E                 KEY_U                 KEY_O
                        BTN_TOUCH
            ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_X     : value 0, min 0, max 19583, fuzz 0, flat 0, resolution 0
                        ABS_MT_POSITION_Y     : value 0, min 0, max 43519, fuzz 0, flat 0, resolution 0
                        ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
    """.trimIndent()

    @Test
    fun parsesTheRealMtkTouchscreen() {
        val devices = GetEventDiscovery.parse(realOutput)

        assertEquals(1, devices.size)
        val device = devices.single()
        assertEquals("/dev/input/event2", device.path)
        assertEquals("mtk-tpd", device.name)
        assertEquals(19583, device.maxX)
        assertEquals(43519, device.maxY)
        assertEquals(9, device.maxSlots)
        assertTrue(device.direct)
    }

    @Test
    fun pickReturnsTheDirectTouchscreen() {
        val device = GetEventDiscovery.pickTouchscreen(realOutput)

        assertNotNull(device)
        assertEquals("/dev/input/event2", device?.path)
    }

    @Test
    fun prefersTheDirectTouchscreenOverAnotherAbsDevice() {
        val output = """
            add device 1: /dev/input/event3
              name:     "stylus-pad"
              events:
                ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 3, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_X     : value 0, min 0, max 5000, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 5000, fuzz 0, flat 0, resolution 0
            add device 2: /dev/input/event2
              name:     "mtk-tpd"
              events:
                ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_X     : value 0, min 0, max 19583, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 43519, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
        """.trimIndent()

        val device = GetEventDiscovery.pickTouchscreen(output)

        assertEquals("/dev/input/event2", device?.path)
        assertEquals(19583, device?.maxX)
    }

    @Test
    fun parsesNumericAbsCodesAndOptionalSlots() {
        val output = """
            add device 1: /dev/input/event5
              name:     "numeric-touch"
              events:
                ABS (0003): 002f   : value 0, min 0, max 4, fuzz 0, flat 0, resolution 0
                            0035   : value 0, min 0, max 999, fuzz 0, flat 0, resolution 0
                            0036   : value 0, min 0, max 1999, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
        """.trimIndent()

        val device = GetEventDiscovery.parse(output).single()

        assertEquals(999, device.maxX)
        assertEquals(1999, device.maxY)
        assertEquals(4, device.maxSlots)
    }

    @Test
    fun ignoresDevicesWithoutPositionAxes() {
        val output = """
            add device 1: /dev/input/event0
              name:     "gpio-keys"
              events:
                KEY (0001): KEY_VOLUMEUP          KEY_VOLUMEDOWN
        """.trimIndent()

        assertTrue(GetEventDiscovery.parse(output).isEmpty())
        assertNull(GetEventDiscovery.pickTouchscreen(output))
        assertNull(GetEventDiscovery.pickTouchscreen(""))
    }
}
