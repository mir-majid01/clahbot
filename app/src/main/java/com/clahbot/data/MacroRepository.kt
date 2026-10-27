package com.clahbot.data

import android.content.Context
import com.clahbot.data.models.GesturePath
import com.clahbot.data.models.GesturePoint
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.execution.MacroTimeline
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
 * Versioned JSON store for macros and the single authoritative macro list.
 *
 * Every mutation publishes the new list through [macros], so the dashboard and
 * the floating controller can never drift apart. One file, one writer,
 * serialized by a [Mutex], written via a temp file and a rename so a crash
 * cannot leave a half written library. Corrupt files are quarantined.
 */
class MacroRepository(private val file: File) {

    private val mutex = Mutex()
    private val gson = Gson()

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    suspend fun refresh(): List<Macro> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = readLocked().sortedByDescending { it.createdAt }
            _macros.value = list
            list
        }
    }

    suspend fun load(): List<Macro> = refresh()

    suspend fun get(id: Long): Macro? = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked().firstOrNull { it.id == id } }
    }

    suspend fun save(macro: Macro): Macro? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sanitized = sanitizeMacro(macro) ?: return@withLock null
            val all = readLocked().toMutableList()
            val index = all.indexOfFirst { it.id == sanitized.id }
            if (index >= 0) all[index] = sanitized else all.add(sanitized)
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

    suspend fun rename(id: Long, name: String): Macro? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@withLock null
            val all = readLocked().toMutableList()
            val index = all.indexOfFirst { it.id == id }
            if (index < 0) return@withLock null
            val updated = all[index].copy(name = trimmed)
            all[index] = updated
            writeLocked(all)
            publish(all)
            updated
        }
    }

    suspend fun updatePlayback(id: Long, speedMultiplier: Float, repeatMode: String): Macro? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val all = readLocked().toMutableList()
                val index = all.indexOfFirst { it.id == id }
                if (index < 0) return@withLock null
                val updated = sanitizeMacro(
                    all[index].copy(
                        speedMultiplier = speedMultiplier,
                        repeatMode = repeatMode
                    )
                ) ?: return@withLock null
                all[index] = updated
                writeLocked(all)
                publish(all)
                updated
            }
        }

    /** Size of the macro file on disk, for the storage section in Settings. */
    fun storageBytes(): Long = if (file.exists()) file.length() else 0L

    private fun publish(macros: List<Macro>) {
        _macros.value = macros.sortedByDescending { it.createdAt }
    }

    private fun readLocked(): List<Macro> {
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (_: Exception) {
            return emptyList()
        }
        if (text.isBlank()) return emptyList()

        val parsed: MacroFile? = try {
            gson.fromJson(text, MacroFile::class.java)
        } catch (_: RuntimeException) {
            quarantine()
            return emptyList()
        }
        if (parsed == null) {
            quarantine()
            return emptyList()
        }

        val rawMacros: List<Macro>? = parsed.macros
        return rawMacros.orEmpty().mapNotNull { sanitizeMacro(it) }
    }

    private fun writeLocked(macros: List<Macro>) {
        val envelope = MacroFile(
            schemaVersion = Macro.CURRENT_SCHEMA_VERSION,
            macros = macros.sortedByDescending { it.createdAt }
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
            // An unreadable file is treated as empty.
        }
    }

    private data class MacroFile(
        val schemaVersion: Int = Macro.CURRENT_SCHEMA_VERSION,
        val macros: List<Macro> = emptyList()
    )

    companion object {
        const val FILE_NAME = "macros.json"

        @Volatile
        private var instance: MacroRepository? = null

        fun get(context: Context): MacroRepository {
            return instance ?: synchronized(this) {
                instance ?: MacroRepository(
                    File(context.applicationContext.filesDir, FILE_NAME)
                ).also { instance = it }
            }
        }
    }
}

/**
 * Validates and upgrades a stored macro. Returns null when the data cannot be
 * played back at all, so a corrupt entry never becomes a "saved" macro.
 */
internal fun sanitizeMacro(raw: Macro): Macro? {
    return try {
        val rawGestures: List<RecordedGesture>? = raw.gestures
        if (rawGestures == null) return null

        val gestures = rawGestures
            .mapNotNull { sanitizeGesture(it) }
            .sortedBy { it.timestampMs }
        if (gestures.isEmpty()) return null

        val rawName: String? = raw.name
        val name = rawName.orEmpty().trim().ifBlank { "Macro" }

        val rawRepeat: String? = raw.repeatMode
        val repeatMode = when {
            rawRepeat == Macro.REPEAT_INFINITE -> Macro.REPEAT_INFINITE
            rawRepeat == Macro.REPEAT_ONCE -> Macro.REPEAT_ONCE
            else -> rawRepeat?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.toString()
                ?: Macro.REPEAT_ONCE
        }

        val speed = if (raw.speedMultiplier <= 0f) 1f else MacroTimeline.clampSpeed(raw.speedMultiplier)

        val rawOrientation: String? = raw.orientation
        val orientation = if (rawOrientation == "landscape") "landscape" else "portrait"

        raw.copy(
            name = name,
            gestures = gestures,
            durationMs = MacroTimeline.totalDuration(gestures),
            repeatMode = repeatMode,
            speedMultiplier = speed,
            repeatDelayMs = raw.repeatDelayMs?.coerceIn(0L, Macro.MAX_REPEAT_DELAY_MS),
            orientation = orientation,
            schemaVersion = Macro.CURRENT_SCHEMA_VERSION
        )
    } catch (_: RuntimeException) {
        null
    }
}

private fun sanitizeGesture(raw: RecordedGesture): RecordedGesture? {
    return try {
        val rawPoints: List<GesturePoint>? = raw.points
        val cleaned = rawPoints.orEmpty()
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map {
                GesturePoint(
                    x = it.x.coerceIn(0f, 1f),
                    y = it.y.coerceIn(0f, 1f),
                    t = it.t.coerceAtLeast(0L)
                )
            }

        val type = when (raw.gestureType) {
            "swipe" -> "swipe"
            "long_press" -> "long_press"
            else -> "tap"
        }

        // Macros recorded before schema 2 have no path; rebuild a straight line
        // so they still replay instead of being discarded.
        val points = if (cleaned.size >= 2) cleaned else MacroTimeline.fallbackPoints(raw)
        if (points.isEmpty()) return null

        val rawPaths: List<GesturePath?>? = raw.paths
        val cleanedPaths = rawPaths.orEmpty()
            .take(MacroTimeline.MAX_STROKE_COUNT)
            .mapNotNull { path ->
                if (path == null) return@mapNotNull null
                val pathPoints: List<GesturePoint>? = path.points
                val pathCleaned = pathPoints.orEmpty()
                    .filter { it.x.isFinite() && it.y.isFinite() }
                    .map {
                        GesturePoint(
                            x = it.x.coerceIn(0f, 1f),
                            y = it.y.coerceIn(0f, 1f),
                            t = it.t.coerceIn(0L, MacroTimeline.MAX_STROKE_MS)
                        )
                    }
                if (pathCleaned.size < 2) return@mapNotNull null
                GesturePath(
                    startOffsetMs = path.startOffsetMs.coerceIn(
                        0L,
                        MacroTimeline.MAX_STROKE_MS
                    ),
                    points = pathCleaned
                )
            }
        val paths = if (cleanedPaths.size >= 2) cleanedPaths else emptyList()
        val finalPoints = if (paths.isNotEmpty()) paths.first().points else points

        val duration = maxOf(
            raw.durationMs.coerceIn(1L, MacroTimeline.MAX_STROKE_MS),
            paths.maxOfOrNull { it.startOffsetMs + it.durationMs } ?: 0L
        ).coerceIn(1L, MacroTimeline.MAX_STROKE_MS)

        raw.copy(
            timestampMs = raw.timestampMs.coerceAtLeast(0L),
            durationMs = duration,
            gestureType = type,
            normalizedStartX = finalPoints.first().x,
            normalizedStartY = finalPoints.first().y,
            normalizedEndX = finalPoints.last().x,
            normalizedEndY = finalPoints.last().y,
            points = finalPoints,
            paths = paths
        )
    } catch (_: RuntimeException) {
        null
    }
}
