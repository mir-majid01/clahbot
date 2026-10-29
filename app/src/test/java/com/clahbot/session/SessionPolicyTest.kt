package com.clahbot.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyTest {

    @Test
    fun shortAppSwitchKeepsTheSession() {
        val left = SessionPolicy.nextLeftAt(1_000L, null, gameForeground = false)
        assertEquals(1_000L, left)
        assertFalse(SessionPolicy.shouldClose(50_000L, left))
        val back = SessionPolicy.nextLeftAt(50_000L, left, gameForeground = true)
        assertNull(back)
        assertFalse(SessionPolicy.shouldClose(50_000L, back))
    }

    @Test
    fun sixtySecondsAwayClosesExactlyAtTheBoundary() {
        assertFalse(SessionPolicy.shouldClose(60_999L, 1_000L))
        assertTrue(SessionPolicy.shouldClose(61_000L, 1_000L))
        assertTrue(SessionPolicy.shouldClose(90_000L, 1_000L))
    }

    @Test
    fun leavingAgainResetsTheGraceWindow() {
        val first = SessionPolicy.nextLeftAt(1_000L, null, false)
        val stillOut = SessionPolicy.nextLeftAt(2_000L, first, false)
        assertEquals(first, stillOut)
        val back = SessionPolicy.nextLeftAt(3_000L, stillOut, true)
        assertNull(back)
        val leftAgain = SessionPolicy.nextLeftAt(4_000L, back, false)
        assertEquals(4_000L, leftAgain)
        assertFalse(SessionPolicy.shouldClose(5_000L, leftAgain))
    }
}
