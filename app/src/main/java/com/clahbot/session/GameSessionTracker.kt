package com.clahbot.session

import android.content.Context
import com.clahbot.data.AttackRepository
import com.clahbot.data.SessionRepository
import com.clahbot.data.models.GameSession
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the live game session: starts it on a Clash launch or playback, keeps
 * its totals in step with saved attacks, and closes it after 60 s away from
 * the game or when the controller closes.
 */
object GameSessionTracker {

    private const val POLL_MS = 5_000L
    private const val TOUCH_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    @Volatile
    private var leftForegroundAt: Long? = null

    @Volatile
    private var setupJob: Job? = null
    private lateinit var sessionRepository: SessionRepository
    private lateinit var attackRepository: AttackRepository

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            sessionRepository = SessionRepository.get(context.applicationContext)
            attackRepository = AttackRepository.get(context.applicationContext)
            setupJob = scope.launch {
                sessionRepository.recoverOpenSessions()
                sessionRepository.refresh()
            }
            scope.launch {
                attackRepository.records.collect { records ->
                    sessionRepository.syncWithRecords(records)
                }
            }
            scope.launch {
                while (isActive) {
                    tick()
                    delay(POLL_MS)
                }
            }
        }
    }

    suspend fun ensureSession(startedAt: Long = System.currentTimeMillis()): GameSession {
        setupJob?.join()
        val session = sessionRepository.ensureActive(startedAt)
        sessionRepository.refresh()
        return session
    }

    suspend fun noteMacro(macroId: Long) {
        if (macroId <= 0L) return
        setupJob?.join()
        sessionRepository.addMacroToActive(macroId)
        sessionRepository.refresh()
    }

    suspend fun syncNow(includeIds: Set<Long> = emptySet()) {
        setupJob?.join()
        sessionRepository.syncWithRecords(attackRepository.records.value, includeIds)
    }

    fun closeSessionAsync(status: String = GameSession.CLOSED) {
        scope.launch { closeSession(status) }
    }

    suspend fun closeSession(status: String = GameSession.CLOSED) {
        setupJob?.join()
        sessionRepository.syncWithRecords(attackRepository.records.value)
        sessionRepository.closeActive(status)
        sessionRepository.refresh()
        leftForegroundAt = null
    }

    private suspend fun tick() {
        val active = sessionRepository.active() ?: run {
            leftForegroundAt = null
            return
        }
        val now = System.currentTimeMillis()
        val foreground = ClahAccessibilityService.isGameForeground()
        leftForegroundAt = SessionPolicy.nextLeftAt(now, leftForegroundAt, foreground)
        if (foreground) {
            if (now - active.lastUpdatedAt >= TOUCH_MS) {
                sessionRepository.touchActive(now)
            }
            return
        }
        val leftAt = leftForegroundAt
        if (SessionPolicy.shouldClose(now, leftAt)) {
            sessionRepository.syncWithRecords(attackRepository.records.value)
            sessionRepository.closeActive(GameSession.CLOSED, leftAt ?: now)
            sessionRepository.refresh()
            leftForegroundAt = null
        }
    }
}
