package com.clahbot.data

import android.content.Context
import com.clahbot.data.models.AttackRecord
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
 * Local attack history. Kept deliberately separate from macros so deleting a
 * macro never deletes its past attacks. Same atomic-write pattern as macros.
 */
class AttackRepository(private val file: File) {

    private val mutex = Mutex()
    private val gson = Gson()

    private val _records = MutableStateFlow<List<AttackRecord>>(emptyList())
    val records: StateFlow<List<AttackRecord>> = _records.asStateFlow()

    suspend fun refresh(): List<AttackRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readLocked().sortedByDescending { it.startedAt }
            _records.value = list
            list
        }
    }

    suspend fun add(record: AttackRecord): AttackRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sanitized = sanitizeAttack(record) ?: return@withLock null
            val all = readLocked().toMutableList()
            if (all.any { it.id == sanitized.id }) return@withLock null
            all.add(sanitized)
            writeLocked(all)
            publish(all)
            sanitized
        }
    }

    suspend fun update(record: AttackRecord): AttackRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sanitized = sanitizeAttack(record) ?: return@withLock null
            val all = readLocked().toMutableList()
            val index = all.indexOfFirst { it.id == sanitized.id }
            if (index < 0) return@withLock null
            all[index] = sanitized
            writeLocked(all)
            publish(all)
            sanitized
        }
    }

    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readLocked().toMutableList()
            val removed = all.removeAll { it.id == id }
            if (removed) {
                writeLocked(all)
                publish(all)
            }
            removed
        }
    }

    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeLocked(emptyList())
            publish(emptyList())
            true
        }
    }

    fun storageBytes(): Long = if (file.exists()) file.length() else 0L

    private fun publish(records: List<AttackRecord>) {
        _records.value = records.sortedByDescending { it.startedAt }
    }

    private fun readLocked(): List<AttackRecord> {
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return emptyList()
        }
        if (text.isBlank()) return emptyList()
        val parsed: AttackFile? = try {
            gson.fromJson(text, AttackFile::class.java)
        } catch (_: RuntimeException) {
            quarantine()
            return emptyList()
        }
        val raw: List<AttackRecord>? = parsed?.records
        return raw.orEmpty().mapNotNull { sanitizeAttack(it) }
    }

    private fun writeLocked(records: List<AttackRecord>) {
        val envelope = AttackFile(
            schemaVersion = SCHEMA_VERSION,
            records = records.sortedByDescending { it.startedAt }
        )
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
            // Unreadable history is treated as empty.
        }
    }

    private data class AttackFile(
        val schemaVersion: Int = SCHEMA_VERSION,
        val records: List<AttackRecord> = emptyList()
    )

    companion object {
        const val SCHEMA_VERSION = 2
        const val FILE_NAME = "attacks.json"

        @Volatile
        private var instance: AttackRepository? = null

        fun get(context: Context): AttackRepository {
            return instance ?: synchronized(this) {
                instance ?: AttackRepository(
                    File(context.applicationContext.filesDir, FILE_NAME)
                ).also { instance = it }
            }
        }
    }
}

/** Drops records that cannot describe an attack; keeps incomplete ones. */
internal fun sanitizeAttack(raw: AttackRecord): AttackRecord? {
    return try {
        val started = raw.startedAt.coerceAtLeast(0L)
        if (started <= 0L) return null
        val ended = raw.endedAt.coerceAtLeast(started)
        val duration = if (raw.durationMs > 0) raw.durationMs else (ended - started)
        val result = when (raw.result) {
            AttackRecord.RESULT_COMPLETED -> AttackRecord.RESULT_COMPLETED
            AttackRecord.RESULT_INTERRUPTED -> AttackRecord.RESULT_INTERRUPTED
            else -> AttackRecord.RESULT_UNKNOWN
        }
        val source = if (raw.lootSource == AttackRecord.LOOT_MANUAL) {
            AttackRecord.LOOT_MANUAL
        } else {
            AttackRecord.LOOT_NONE
        }
        raw.copy(
            startedAt = started,
            endedAt = ended,
            durationMs = duration.coerceAtLeast(0L),
            gold = raw.gold?.coerceAtLeast(0L),
            elixir = raw.elixir?.coerceAtLeast(0L),
            darkElixir = raw.darkElixir?.coerceAtLeast(0L),
            bonusGold = raw.bonusGold?.coerceAtLeast(0L),
            bonusElixir = raw.bonusElixir?.coerceAtLeast(0L),
            bonusDarkElixir = raw.bonusDarkElixir?.coerceAtLeast(0L),
            result = result,
            lootSource = source,
            macroName = raw.macroName.orEmpty().ifBlank { "Unknown macro" },
            speedMultiplier = if (raw.speedMultiplier <= 0f) 1f else raw.speedMultiplier
        )
    } catch (_: RuntimeException) {
        null
    }
}
