package com.clahbot.gesture

import com.clahbot.game.GameState
import com.clahbot.game.ScreenBox
import com.clahbot.game.ScreenObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureAuthorityTest {

    private lateinit var authority: GestureAuthority

    @Before
    fun setUp() {
        authority = GestureAuthority()
        // These tests are about screen ownership between transactions, not about
        // the Allow-Tapping switch. Opening the gate here keeps each test focused
        // on ownership; the gate itself is covered by
        // [AllowTappingGateTest], which runs with the production default (off).
        authority.setAllowWallTapping(true)
    }

    @Test
    fun `exclusive ownership held from row tap through verification`() = runBlocking {
        // Simulate a wall upgrade transaction starting
        authority.startWallTxn(42L)

        // First gesture (row tap) should pass preflight
        val rowCmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
            sessionId = 42L, transactionId = 1L,
            frameHash = "rowHash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertEquals(PreflightResult.Allowed, authority.preflightCheck(rowCmd, ScreenObservation(
            state = GameState.HOME, confidence = 0.9f, hash = "rowHash"
        )))

        // Second gesture (confirm tap) should also pass — ownership continuous
        val confirmCmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(300, 300, 400, 400)),
            sessionId = 42L, transactionId = 2L,
            frameHash = "confirmHash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertEquals(PreflightResult.Allowed, authority.preflightCheck(confirmCmd, ScreenObservation(
            state = GameState.HOME, confidence = 0.9f, hash = "confirmHash"
        )))

        // Third gesture (different session) must be blocked — ownership still held
        val otherCmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(500, 500, 600, 600)),
            sessionId = 99L, transactionId = 3L,
            frameHash = "otherHash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertTrue("Third gesture must be blocked while wall txn owns screen",
            authority.preflightCheck(otherCmd, ScreenObservation(
                state = GameState.HOME, confidence = 0.9f, hash = "otherHash"
            )) is PreflightResult.Blocked)
        // Even with different transactionId, same sessionId should still be blocked
        // because wallTxnId=42 blocks any other session

        // Manual Stop releases ownership
        authority.cancelAll()
        assertTrue("After cancelAll, ownership released",
            authority.preflightCheck(otherCmd, ScreenObservation(
                state = GameState.HOME, confidence = 0.9f, hash = "otherHash"
            )) == PreflightResult.Allowed)
    }

    @Test
    fun `another automation task cannot dispatch between row tap and confirmation`() = runBlocking {
        authority.startWallTxn(42L)

        // Wall row tap command
        val rowCmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
            sessionId = 42L, transactionId = 1L,
            frameHash = "rowHash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertEquals(PreflightResult.Allowed, authority.preflightCheck(rowCmd, ScreenObservation(
            state = GameState.HOME, confidence = 0.9f, hash = "rowHash"
        )))

        // Attack macro from different session — must be blocked
        val attackCmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(500, 500, 600, 600)),
            sessionId = 99L, transactionId = 2L,
            frameHash = "", captureTimestampMillis = 0L,
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.ATTACK_MACRO,
            screenOwnership = ScreenOwnership.SHARED,
            cancelToken = CancelToken()
        )
        assertTrue("Attack macro must be blocked while wall txn owns screen",
            authority.preflightCheck(attackCmd, null) is PreflightResult.Blocked)

        // Recovery global back — must also be blocked
        val backCmd = GestureCommand(
            kind = GestureCommand.Kind.GlobalBack,
            sessionId = 77L, transactionId = 3L,
            frameHash = "", captureTimestampMillis = 0L,
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.SHARED,
            screenOwnership = ScreenOwnership.SHARED,
            cancelToken = CancelToken()
        )
        assertTrue("Global BACK must be blocked while wall txn owns screen",
            authority.preflightCheck(backCmd, null) is PreflightResult.Blocked)
    }

    @Test
    fun `ownership released after transaction completes`() = runBlocking {
        authority.startWallTxn(42L)

        // Simulate transaction completion
        authority.endWallTxn()

        // Now other commands should proceed
        val cmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
            sessionId = 99L, transactionId = 1L,
            frameHash = "hash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertEquals(PreflightResult.Allowed, authority.preflightCheck(cmd, ScreenObservation(
            state = GameState.HOME, confidence = 0.9f, hash = "hash"
        )))

        // Attack macro should also proceed after wall txn ended
        val attack = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(300, 300, 400, 400)),
            sessionId = 99L, transactionId = 2L,
            frameHash = "", captureTimestampMillis = 0L,
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.ATTACK_MACRO,
            screenOwnership = ScreenOwnership.SHARED,
            cancelToken = CancelToken()
        )
        // Note: with no observation, attack macro checks only ownership gate
        assertEquals(PreflightResult.Allowed, authority.preflightCheck(attack, null))
    }

    @Test
    fun `a wall-list scan blocks every authority gesture while it owns the screen`() = runBlocking {
        val scanId = 777L
        val held = ScanGate.begin(scanId)
        try {
            assertTrue("a scan acquires the gate", held)

            val wallCmd = GestureCommand(
                kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
                sessionId = 42L, transactionId = 1L,
                frameHash = "hash", captureTimestampMillis = System.currentTimeMillis(),
                expiresAtMillis = System.currentTimeMillis() + 10_000,
                priority = GesturePriority.WALL_UPGRADE,
                screenOwnership = ScreenOwnership.EXCLUSIVE,
                cancelToken = CancelToken()
            )
            assertTrue("wall upgrade blocked while scan scrolls the list",
                authority.preflightCheck(wallCmd, ScreenObservation(
                    state = GameState.HOME, confidence = 0.9f, hash = "hash"
                )) is PreflightResult.Blocked)

            val attackCmd = GestureCommand(
                kind = GestureCommand.Kind.Tap(ScreenBox(300, 300, 400, 400)),
                sessionId = 99L, transactionId = 2L,
                frameHash = "", captureTimestampMillis = 0L,
                expiresAtMillis = System.currentTimeMillis() + 10_000,
                priority = GesturePriority.ATTACK_MACRO,
                screenOwnership = ScreenOwnership.SHARED,
                cancelToken = CancelToken()
            )
            assertTrue("attack macro blocked while scan scrolls the list",
                authority.preflightCheck(attackCmd, null) is PreflightResult.Blocked)
        } finally {
            // Never leak the gate into other tests.
            ScanGate.end(scanId)
        }

        assertFalse(ScanGate.isActive())
        val cmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
            sessionId = 42L, transactionId = 1L,
            frameHash = "hash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertEquals(PreflightResult.Allowed, authority.preflightCheck(cmd, ScreenObservation(
            state = GameState.HOME, confidence = 0.9f, hash = "hash"
        )))
    }

    @Test
    fun `ownership released after failure`() = runBlocking {
        authority.startWallTxn(42L)

        // Simulate transaction failure
        authority.endWallTxn()

        val cmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
            sessionId = 99L, transactionId = 1L,
            frameHash = "hash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertEquals(PreflightResult.Allowed, authority.preflightCheck(cmd, ScreenObservation(
            state = GameState.HOME, confidence = 0.9f, hash = "hash"
        )))
    }

    @Test
    fun `ownership released after manual Stop`() = runBlocking {
        authority.startWallTxn(42L)

        // Manual Stop cancels everything
        authority.cancelAll()

        val cmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
            sessionId = 99L, transactionId = 1L,
            frameHash = "hash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        // After cancelAll, wallTxnId=null, so ownership is released
        assertEquals(PreflightResult.Allowed, authority.preflightCheck(cmd, ScreenObservation(
            state = GameState.HOME, confidence = 0.9f, hash = "hash"
        )))
    }

    // ---- NEW: competing transaction ownership tests ----

    @Test
    fun `second startWallTxn returns false while another transaction is active`() = runBlocking {
        // First transaction acquires ownership
        assertTrue("First startWallTxn must acquire ownership",
            authority.startWallTxn(42L))

        // Second transaction must NOT acquire ownership
        assertFalse("Second startWallTxn must not acquire ownership while first is active",
            authority.startWallTxn(99L))

        // Ownership must still belong to the first transaction — second session is blocked
        val otherCmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
            sessionId = 99L, transactionId = 1L,
            frameHash = "hash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertTrue("Second session command must be blocked while first txn active",
            authority.preflightCheck(otherCmd, ScreenObservation(
                state = GameState.HOME, confidence = 0.9f, hash = "hash"
            )) is PreflightResult.Blocked)
    }

    @Test
    fun `caller can detect failed ownership acquisition`() = runBlocking {
        authority.startWallTxn(42L)

        // The second caller gets false — it cannot proceed as if it owns the screen
        val acquired = authority.startWallTxn(99L)
        assertFalse("startWallTxn must return false when another txn is active", acquired)

        // Preflight confirms the second session is blocked
        val cmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
            sessionId = 99L, transactionId = 1L,
            frameHash = "hash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertTrue("Second session command must be blocked",
            authority.preflightCheck(cmd, ScreenObservation(
                state = GameState.HOME, confidence = 0.9f, hash = "hash"
            )) is PreflightResult.Blocked)
    }

    @Test
    fun `startWallTxn succeeds after endWallTxn releases ownership`() = runBlocking {
        assertTrue("First startWallTxn acquires ownership",
            authority.startWallTxn(42L))

        assertTrue("endWallTxn releases ownership", authority.endWallTxn())

        // A new transaction can now acquire ownership — first session is released
        assertTrue("Second startWallTxn acquires after endWallTxn",
            authority.startWallTxn(99L))

        // First session is now blocked, second session is allowed
        val firstCmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(100, 100, 200, 200)),
            sessionId = 42L, transactionId = 1L,
            frameHash = "hash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertTrue("First session must be blocked after endWallTxn",
            authority.preflightCheck(firstCmd, ScreenObservation(
                state = GameState.HOME, confidence = 0.9f, hash = "hash"
            )) is PreflightResult.Blocked)

        val secondCmd = GestureCommand(
            kind = GestureCommand.Kind.Tap(ScreenBox(300, 300, 400, 400)),
            sessionId = 99L, transactionId = 2L,
            frameHash = "hash", captureTimestampMillis = System.currentTimeMillis(),
            expiresAtMillis = System.currentTimeMillis() + 10_000,
            priority = GesturePriority.WALL_UPGRADE,
            screenOwnership = ScreenOwnership.EXCLUSIVE,
            cancelToken = CancelToken()
        )
        assertEquals(PreflightResult.Allowed, authority.preflightCheck(secondCmd, ScreenObservation(
            state = GameState.HOME, confidence = 0.9f, hash = "hash"
        )))
    }

    @Test
    fun `endWallTxn returns false when no transaction is active`() = runBlocking {
        assertFalse("endWallTxn with no active txn returns false",
            authority.endWallTxn())
    }
}

enum class ScreenOwnership { EXCLUSIVE, SHARED }