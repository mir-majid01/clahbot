package com.clahbot.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetEventParserTest {

    private val device = TouchscreenDevice(
        path = "/dev/input/event2",
        name = "mtk-tpd",
        maxX = 19583,
        maxY = 43519,
        maxSlots = 9,
        direct = true
    )

    private val frames = mutableListOf<RawTouchFrame>()
    private val parser = GetEventParser(device) { frames += it }

    @Test
    fun parsesTheRealTouchDownMoveAndUpSequence() {
        parser.feed("[  154475.905895] EV_ABS       ABS_MT_TRACKING_ID   00009cc4            ")
        parser.feed("[  154475.905895] EV_ABS       ABS_MT_POSITION_X    000039d4            ")
        parser.feed("[  154475.905895] EV_ABS       ABS_MT_POSITION_Y    00008a44            ")
        parser.feed("[  154475.905895] EV_KEY       BTN_TOUCH            DOWN                ")
        parser.feed("[  154475.905895] EV_SYN       SYN_REPORT           00000000            ")
        parser.feed("[  154476.041821] EV_ABS       ABS_MT_POSITION_X    000039c4            ")
        parser.feed("[  154476.041821] EV_ABS       ABS_MT_POSITION_Y    000089d8            ")
        parser.feed("[  154476.041821] EV_SYN       SYN_REPORT           00000000            ")
        parser.feed("[  154730.922955] EV_ABS       ABS_MT_TRACKING_ID   ffffffff            ")
        parser.feed("[  154730.922955] EV_KEY       BTN_TOUCH            UP                  ")
        parser.feed("[  154730.922955] EV_SYN       SYN_REPORT           00000000            ")

        assertEquals(3, frames.size)
        assertEquals(154475905L, frames[0].timestampMs)
        assertEquals(1, frames[0].contacts.size)
        assertEquals(0, frames[0].contacts[0].slot)
        assertEquals(40132, frames[0].contacts[0].trackingId)
        assertEquals(14804, frames[0].contacts[0].rawX)
        assertEquals(35396, frames[0].contacts[0].rawY)

        assertEquals(154476041L, frames[1].timestampMs)
        assertEquals(14788, frames[1].contacts[0].rawX)
        assertEquals(35288, frames[1].contacts[0].rawY)

        assertEquals(154730922L, frames[2].timestampMs)
        assertTrue(frames[2].contacts.isEmpty())
    }

    @Test
    fun reportsEveryActiveSlotInAProtocolBFrame() {
        feed(listOf(
            abs("SLOT", 0),
            abs("TRACKING_ID", 1),
            abs("X", 100),
            abs("Y", 200),
            syn()
        ), 1000.0)
        feed(listOf(
            abs("SLOT", 1),
            abs("TRACKING_ID", 2),
            abs("X", 300),
            abs("Y", 400),
            syn()
        ), 1000.01)
        feed(listOf(
            abs("SLOT", 0),
            abs("X", 110),
            syn()
        ), 1000.02)
        feed(listOf(
            abs("SLOT", 0),
            abs("TRACKING_ID", 0xffffffffL),
            syn()
        ), 1000.03)
        feed(listOf(
            abs("SLOT", 1),
            abs("TRACKING_ID", 0xffffffffL),
            syn()
        ), 1000.04)

        assertEquals(5, frames.size)
        assertEquals(listOf(0 to 1), frames[0].contacts.map { it.slot to it.trackingId })
        assertEquals(
            listOf(0 to 1, 1 to 2),
            frames[1].contacts.map { it.slot to it.trackingId }
        )
        assertEquals(110, frames[2].contacts.first { it.slot == 0 }.rawX)
        assertEquals(listOf(1), frames[3].contacts.map { it.slot })
        assertTrue(frames[4].contacts.isEmpty())
    }

    @Test
    fun synDroppedClearsStateAndEmitsAnEmptyFrame() {
        feed(listOf(
            abs("TRACKING_ID", 1),
            abs("X", 100),
            abs("Y", 200),
            syn()
        ), 2000.0)
        feed(listOf("[  2000.100000] EV_SYN       SYN_DROPPED          00000000"), null)
        feed(listOf(
            abs("TRACKING_ID", 3),
            abs("X", 500),
            abs("Y", 600),
            syn()
        ), 2000.2)

        assertEquals(3, frames.size)
        assertTrue(frames[1].contacts.isEmpty())
        assertEquals(3, frames[2].contacts.single().trackingId)
        assertEquals(500, frames[2].contacts.single().rawX)
    }

    @Test
    fun parsesNumericTypeAndCodeTokens() {
        parser.feed("[  3000.000000] 0003 0039 00000007")
        parser.feed("[  3000.000000] 0003 0035 00000064")
        parser.feed("[  3000.000000] 0003 0036 000000c8")
        parser.feed("[  3000.000000] 0000 0000 00000000")

        assertEquals(1, frames.size)
        assertEquals(7, frames[0].contacts.single().trackingId)
        assertEquals(100, frames[0].contacts.single().rawX)
        assertEquals(200, frames[0].contacts.single().rawY)
    }

    @Test
    fun doesNotEmitAFrameWithoutSynReport() {
        parser.feed("[  4000.000000] EV_ABS ABS_MT_TRACKING_ID 00000001")
        parser.feed("[  4000.000000] EV_ABS ABS_MT_POSITION_X 00000064")
        parser.feed("[  4000.000000] EV_ABS ABS_MT_POSITION_Y 000000c8")

        assertTrue(frames.isEmpty())
    }

    @Test
    fun ignoresMalformedLinesAndIncompleteContacts() {
        parser.feed("not an event")
        parser.feed("[bad] EV_ABS ABS_MT_POSITION_X 00000064")
        parser.feed("[  5000.000000] EV_ABS ABS_MT_TRACKING_ID 00000001")
        parser.feed("[  5000.000000] EV_ABS ABS_MT_POSITION_X 00000064")
        parser.feed("[  5000.000000] EV_SYN SYN_REPORT 00000000")

        assertTrue(frames.isEmpty())
    }

    @Test
    fun resetDropsAStuckContact() {
        parser.feed("[  6000.000000] EV_ABS ABS_MT_TRACKING_ID 00000001")
        parser.feed("[  6000.000000] EV_ABS ABS_MT_POSITION_X 00000064")
        parser.feed("[  6000.000000] EV_ABS ABS_MT_POSITION_Y 000000c8")
        parser.feed("[  6000.000000] EV_SYN SYN_REPORT 00000000")
        parser.reset()
        parser.feed("[  6100.000000] EV_ABS ABS_MT_POSITION_X 000000c8")
        parser.feed("[  6100.000000] EV_SYN SYN_REPORT 00000000")

        assertEquals(1, frames.size)
    }

    private fun abs(code: String, value: Long): String {
        val token = when (code) {
            "SLOT" -> "ABS_MT_SLOT"
            "TRACKING_ID" -> "ABS_MT_TRACKING_ID"
            "X" -> "ABS_MT_POSITION_X"
            "Y" -> "ABS_MT_POSITION_Y"
            else -> error("unknown code")
        }
        return "EV_ABS       $token    ${value.toString(16).padStart(8, '0')}"
    }

    private fun syn(): String = "EV_SYN       SYN_REPORT           00000000"

    private fun feed(lines: List<String>, timestampSeconds: Double?) {
        val ts = timestampSeconds ?: run {
            parser.feed(lines.single())
            return
        }
        val stamp = "[  ${"%.6f".format(java.util.Locale.US, ts)}] "
        lines.forEach { parser.feed(stamp + it) }
    }
}
