package com.clahbot.data

import android.content.Context
import com.clahbot.data.models.AttackRecord
import com.clahbot.data.models.GameSession
import com.clahbot.data.models.SessionAggregator
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Additive game-session store. attacks.json is never rewritten; a session only
 * remembers record ids and a recomputable snapshot of their totals.
 */
class SessionRepository(private val file: File) {

    private val mutex = Mutex()
    private val gson = Gson()

    private val _sessions = MutableStateFlow<List<GameSession>>(emptyList())
    val sessions: StateFlow<List<GameSession>> = _sessions.asStateFlow()

    suspend fun refresh(): List<GameSession> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readLocked().sortedByDescending { it.startedAt }
            _sessions.value = list
            list
        }
    }

    /** Called once when the app/accessibility process starts. */
    suspend fun recoverOpenSessions(now: Long = System.currentTimeMillis()): GameSession? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readLocked().toMutableList()
                var recovered: GameSession? = null
                var changed = false
                var index = 0
                while (index < all.size) {
                    val session = all[index]
                    if (!session.active) {
                        index++
                        continue
                    }
                    changed = true
                    if (session.attackCount == 0 && session.macroIds.isEmpty()) {
                        all.removeAt(index)
                        continue
                    }
                    val updated = session.copy(
                        status = GameSession.RECOVERED,
                        endedAt = session.lastUpdatedAt.coerceAtLeast(session.startedAt),
                        lastUpdatedAt = now
                    )
                    all[index] = updated
                    recovered = updated
                    index++
                }
                if (changed) {
                    writeLocked(all)
                    publish(all)
                }
                recovered
            }
        }

    suspend fun active(): GameSession? = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked().firstOrNull { it.active } }
    }

    suspend fun ensureActive(now: Long = System.currentTimeMillis()): GameSession =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readLocked().toMutableList()
                val current = all.firstOrNull { it.active }
                if (current != null) {
                    return@withLock current
                }
                var id = now
                while (all.any { it.id == id }) id++
                val session = GameSession(
                    id = id,
                    startedAt = now,
                    lastUpdatedAt = now
                )
                all.add(session)
                writeLocked(all)
                publish(all)
                session
            }
        }

    suspend fun touchActive(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readLocked().toMutableList()
            val index = all.indexOfFirst { it.active }
            if (index < 0) return@withLock
            all[index] = all[index].copy(lastUpdatedAt = now)
            writeLocked(all)
            publish(all)
        }
    }

    suspend fun addMacroToActive(
        macroId: Long,
        now: Long = System.currentTimeMillis()
    ): GameSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readLocked().toMutableList()
            var index = all.indexOfFirst { it.active }
            if (index < 0) {
                var id = now
                while (all.any { it.id == id }) id++
                all.add(
                    GameSession(
                        id = id,
                        startedAt = now,
                        lastUpdatedAt = now,
                        macroIds = listOf(macroId)
                    )
                )
                index = all.lastIndex
            } else {
                val session = all[index]
                if (macroId !in session.macroIds) {
                    all[index] = session.copy(
                        macroIds = session.macroIds + macroId,
                        lastUpdatedAt = now
                    )
                }
            }
            writeLocked(all)
            publish(all)
            all[index]
        }
    }

    suspend fun syncWithRecords(
        records: List<AttackRecord>,
        includeIds: Set<Long> = emptySet()
    ): GameSession? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readLocked().toMutableList()
                val index = all.indexOfFirst { it.active }
                if (index < 0) return@withLock null
                val session = all[index]
                val ids = session.attackIds.toMutableSet()
                for (record in records) {
                    if (record.confirmed &&
                        (record.startedAt >= session.startedAt || record.id in includeIds)
                    ) {
                        ids.add(record.id)
                    }
                }
                val selected = records.filter { it.id in ids }
                val totals = SessionAggregator.totals(selected)
                val macroIds = (session.macroIds + selected.map { it.macroId })
                    .filter { it > 0 }
                    .distinct()
                val updated = session.copy(
                    attackIds = ids.toList(),
                    macroIds = macroIds,
                    attackCount = totals.attackCount,
                    incompleteAttacks = totals.incompleteAttacks,
                    gold = totals.gold,
                    elixir = totals.elixir,
                    darkElixir = totals.darkElixir,
                    bonusGold = totals.bonusGold,
                    bonusElixir = totals.bonusElixir,
                    bonusDarkElixir = totals.bonusDarkElixir,
                    lastUpdatedAt = System.currentTimeMillis()
                )
                if (updated != session) {
                    all[index] = updated
                    writeLocked(all)
                    publish(all)
                }
                updated
            }
        }

    suspend fun setActiveStopReason(reason: String?, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readLocked().toMutableList()
                val index = all.indexOfFirst { it.active }
                if (index < 0) return@withLock
                all[index] = all[index].copy(stopReason = reason, lastUpdatedAt = now)
                writeLocked(all)
                publish(all)
            }
        }

    suspend fun closeActive(
        status: String = GameSession.CLOSED,
        now: Long = System.currentTimeMillis()
    ): GameSession? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readLocked().toMutableList()
            val index = all.indexOfFirst { it.active }
            if (index < 0) return@withLock null
            val current = all[index]
            val closed = current.copy(
                status = status,
                endedAt = now.coerceAtLeast(current.startedAt),
                lastUpdatedAt = now
            )
            if (closed.attackCount == 0 && closed.macroIds.isEmpty()) {
                all.removeAt(index)
                writeLocked(all)
                publish(all)
                return@withLock closed
            }
            all[index] = closed
            writeLocked(all)
            publish(all)
            closed
        }
    }

    suspend fun get(id: Long): GameSession? = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked().firstOrNull { it.id == id } }
    }

    private fun publish(sessions: List<GameSession>) {
        _sessions.value = sessions.sortedByDescending { it.startedAt }
    }

    private fun readLocked(): List<GameSession> {
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (_: Exception) {
            // Preserve the unreadable original aside before treating sessions as empty, so a
            // later write cannot clobber session history.
            quarantine()
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        val parsed: SessionFile? = try {
            gson.fromJson(text, SessionFile::class.java)
        } catch (_: RuntimeException) {
            quarantine()
            return emptyList()
        }
        if (parsed == null) {
            quarantine()
            return emptyList()
        }
        return parsed.sessions.orEmpty()
            .filter { it.startedAt > 0L }
            .map { session ->
                val status = when (session.status) {
                    GameSession.CLOSED, GameSession.RECOVERED -> session.status
                    else -> GameSession.ACTIVE
                }
                session.copy(
                    status = status,
                    lastUpdatedAt = session.lastUpdatedAt.coerceAtLeast(session.startedAt)
                )
            }
    }

    private fun writeLocked(sessions: List<GameSession>) {
        val envelope = SessionFile(sessions = sessions.sortedByDescending { it.startedAt })
        val text = gson.toJson(envelope)
        val parent = file.parentFile
        parent?.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        try {
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun quarantine() {
        try {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
        } catch (_: Exception) {
            // An unreadable file is treated as empty.
        }
    }

    private data class SessionFile(
        val schemaVersion: Int = GameSession.SCHEMA_VERSION,
        val sessions: List<GameSession> = emptyList()
    )

    companion object {
        const val FILE_NAME = "sessions.json"

        @Volatile
        private var instance: SessionRepository? = null

        fun get(context: Context): SessionRepository =
            instance ?: synchronized(this) {
                instance ?: SessionRepository(
                    File(context.applicationContext.filesDir, FILE_NAME)
                ).also { instance = it }
            }

        fun forFile(file: File): SessionRepository = SessionRepository(file)
    }
}
