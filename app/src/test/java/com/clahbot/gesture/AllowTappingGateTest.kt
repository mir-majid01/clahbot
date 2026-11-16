package com.clahbot.gesture

import com.clahbot.game.GameState
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Allow-Tapping-OFF rule, enforced at the SINGLE dispatch point.
 *
 * Production ships with the switch off. In that state exactly one
 * screen-touching command may reach the screen: the read-only wall-list scroll
 * (owner [GestureAuthority.SCAN_SCROLL_OWNER], reason `scan-scroll...`). Every
 * other wall-pipeline tap or swipe — select, upgrade, confirm, Gem, Goblin — is
 * refused by [GestureAuthority.preflightCheck] even when a caller forgot to
 * check the flag, and the refusal is logged as a blocked GESTURE line.
 *
 * These tests use the production default (off); they never open the gate.
 */
class AllowTappingGateTest {

    private fun authority() = GestureAuthority()

    private fun tap(
        owner: String = "",
        priority: GesturePriority = GesturePriority.WALL_UPGRADE,
        box: ScreenBox = ScreenBox(100, 100, 200, 200),
        transactionId: Long = 1L
    ) = GestureCommand(
        kind = GestureCommand.Kind.Tap(box),
        sessionId = 42L,
        transactionId = transactionId,
        frameHash = "",
        captureTimestampMillis = 0L,
        expiresAtMillis = System.currentTimeMillis() + 10_000,
        priority = priority,
        screenOwnership = ScreenOwnership.SHARED,
        cancelToken = CancelToken(),
        owner = owner,
        reason = "wall-tap"
    )

    private fun swipe(
        owner: String = GestureAuthority.SCAN_SCROLL_OWNER,
        reason: String = "scan-scroll down",
        fromY: Int = 1800,
        toY: Int = 1000
    ) = GestureCommand(
        kind = GestureCommand.Kind.Swipe(1360, fromY, 1360, toY, 350L),
        sessionId = 42L,
        transactionId = 9L,
        frameHash = "",
        captureTimestampMillis = 0L,
        expiresAtMillis = System.currentTimeMillis() + 10_000,
        priority = GesturePriority.SHARED,
        screenOwnership = ScreenOwnership.SHARED,
        cancelToken = CancelToken(),
        owner = owner,
        reason = reason
    )

    private fun observation() = ScreenObservation(
        state = GameState.HOME, confidence = 0.9f, hash = ""
    )

    @After
    fun tearDown() {
        GestureAuthority.replaceShared(null)
    }

    @Test
    fun `a fresh authority refuses wall taps because the switch starts off`() {
        val authority = authority()

        assertFalse("the production default must be off", authority.wallTappingAllowed())
    }

    @Test
    fun `with tapping off a wall upgrade tap is refused at the dispatch point`() = runBlocking {
        val authority = authority()

        val result = authority.preflightCheck(
            tap(owner = "AutoWallsRuntime"), observation()
        )

        assertTrue("a wall tap must be blocked, was $result", result is PreflightResult.Blocked)
        assertTrue(
            "the refusal must name the rule, was ${(result as PreflightResult.Blocked).reason}",
            result.reason.contains("allow tapping is off")
        )
    }

    @Test
    fun `with tapping off an unnamed wall-priority tap is still refused`() = runBlocking {
        // Fail-closed: the priority alone marks the command as wall-pipeline, so a
        // caller that forgot to set an owner cannot slip a tap past the gate.
        val authority = authority()

        val result = authority.preflightCheck(tap(), observation())

        assertTrue(result is PreflightResult.Blocked)
    }

    @Test
    fun `with tapping off the read-only scan scroll is allowed`() = runBlocking {
        val authority = authority()

        assertEquals(
            PreflightResult.Allowed,
            authority.preflightCheck(swipe(), observation())
        )
    }

    @Test
    fun `with tapping off a scan-owned swipe that is not a scan scroll is refused`() = runBlocking {
        // The same owner, but a swipe that is not the read-only scroll (a
        // selection drag, for example) must not inherit the exemption.
        val authority = authority()

        val result = authority.preflightCheck(
            swipe(owner = GestureAuthority.SCAN_SCROLL_OWNER, reason = "select-wall"),
            observation()
        )

        assertTrue(result is PreflightResult.Blocked)
    }

    @Test
    fun `with tapping off a tap from the scan owner is refused`() = runBlocking {
        val authority = authority()

        val result = authority.preflightCheck(
            tap(owner = GestureAuthority.SCAN_SCROLL_OWNER), observation()
        )

        assertTrue("a scan-owner TAP is not the scroll", result is PreflightResult.Blocked)
    }

    @Test
    fun `with tapping off a macro swipe is still allowed`() = runBlocking {
        // Attack macros are a different pipeline: the wall switch must not
        // silently disable them.
        val authority = authority()

        val macro = GestureCommand(
            kind = GestureCommand.Kind.Swipe(100, 100, 200, 200, 200L),
            sessionId = 5L,
            transactionId = 3L,
            frameHash = "",
            captureTimestampMillis = 0L,
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.ATTACK_MACRO,
            screenOwnership = ScreenOwnership.SHARED,
            cancelToken = CancelToken(),
            owner = "MacroPlayer",
            reason = "macro step 1"
        )

        assertEquals(PreflightResult.Allowed, authority.preflightCheck(macro, observation()))
    }

    @Test
    fun `the refusal happens on the dispatch path and sends nothing`() = runBlocking {
        // No accessibility service is bound. A refused command must fail BEFORE
        // the service lookup, which proves nothing could have touched the screen.
        val authority = authority()

        val result = authority.enqueueAndAwait(tap(owner = "AutoWallsRuntime"), observation())

        assertTrue("expected a blocked failure, was $result", result is GestureResult.Failed)
        assertTrue(
            "the failure must quote the gate, was ${(result as GestureResult.Failed).reason}",
            result.reason.contains("allow tapping is off")
        )
        assertFalse(authority.isInFlight())
    }

    @Test
    fun `the scan scroll reaches dispatch and fails only because no service is bound`() = runBlocking {
        // Proves the exemption is not merely a preflight allowance: the command
        // passes preflight and stops at the missing accessibility service.
        val authority = authority()

        val result = authority.enqueueAndAwait(swipe(), observation())

        assertTrue(result is GestureResult.Failed)
        assertTrue(
            "expected the service lookup, was ${(result as GestureResult.Failed).reason}",
            result.reason.contains("accessibility service unavailable")
        )
    }

    @Test
    fun `shared returns one authority and replaceShared swaps it`() {
        GestureAuthority.replaceShared(null)
        val first = GestureAuthority.shared()
        val second = GestureAuthority.shared()

        assertSame("every surface must get the same instance", first, second)

        val replacement = GestureAuthority()
        GestureAuthority.replaceShared(replacement)

        assertSame(replacement, GestureAuthority.shared())
        assertNotEquals(first, GestureAuthority.shared())

        GestureAuthority.replaceShared(null)
    }

    @Test
    fun `the shared authority starts with tapping off`() {
        GestureAuthority.replaceShared(null)

        assertFalse(GestureAuthority.shared().wallTappingAllowed())

        GestureAuthority.replaceShared(null)
    }
}
