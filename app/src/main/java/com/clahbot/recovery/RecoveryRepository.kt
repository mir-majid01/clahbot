package com.clahbot.recovery

import android.content.Context
import android.graphics.Bitmap
import com.clahbot.results.ScreenHash
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RecoveryStats(
    val recovered: Int = 0,
    val stopped: Int = 0,
    val unknown: Int = 0
)

data class UnknownScreen(
    val id: String,
    val file: String,
    val at: Long,
    /** Where it appeared: "during Loop 3", "after Return Home", "Android dialog". */
    val context: String,
    val taught: Boolean = false
)

data class RecoveryFile(
    @SerializedName("stats") val stats: RecoveryStats = RecoveryStats(),
    @SerializedName("rules") val rules: List<RecoveryRule> = emptyList(),
    @SerializedName("unknown") val unknown: List<UnknownScreen> = emptyList(),
    /** Rolling hourly recovery attempt timestamps (epoch ms). */
    @SerializedName("attempts") val attempts: List<Long> = emptyList()
)

/**
 * Persists recovery counters, user-taught rules and the capped Unknown
 * screens gallery (screenshots in files/unknown_screens/).
 */
class RecoveryRepository internal constructor(
    private val file: File,
    private val dir: File
) {

    constructor(context: Context) : this(
        File(context.filesDir, FILE_NAME),
        File(context.filesDir, SCREEN_DIR)
    )

    private val gson = Gson()

    private val mutable = MutableStateFlow(load())
    val state: StateFlow<RecoveryFile> = mutable

    // Load at construction: the watchdog writes from the service without a prior refresh().
    private fun load(): RecoveryFile {
        if (!file.exists()) return RecoveryFile()
        val parsed = runCatching { gson.fromJson(file.readText(), RecoveryFile::class.java) }
            .getOrElse {
                quarantine()
                return RecoveryFile()
            }
        if (parsed == null) {
            quarantine()
            return RecoveryFile()
        }
        return parsed
    }

    @Synchronized
    fun refresh(): RecoveryFile {
        val loaded = load()
        mutable.value = loaded
        return loaded
    }

    private fun quarantine() {
        try {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
        } catch (_: Exception) {
            // An unreadable file is treated as empty; nothing else to preserve.
        }
    }

    private fun update(transform: (RecoveryFile) -> RecoveryFile) {
        val next = transform(mutable.value)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(gson.toJson(next))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(next))
                tmp.delete()
            }
        }
        mutable.value = next
    }

    fun noteRecovered() = update {
        it.copy(stats = it.stats.copy(recovered = it.stats.recovered + 1))
    }

    fun noteStopped() = update {
        it.copy(stats = it.stats.copy(stopped = it.stats.stopped + 1))
    }

    fun noteAttempt(at: Long = System.currentTimeMillis()) = update {
        val hourAgo = at - HOUR_MS
        it.copy(attempts = (it.attempts + at).filter { time -> time >= hourAgo })
    }

    fun attemptsInLastHour(now: Long = System.currentTimeMillis()): Int {
        val hourAgo = now - HOUR_MS
        return mutable.value.attempts.count { it >= hourAgo }
    }

    /** Saves the screenshot, caps the gallery, returns the entry or null. */
    @Synchronized
    fun addUnknownScreen(bitmap: Bitmap, context: String): UnknownScreen? {
        runCatching {
            dir.mkdirs()
            val name = "unknown_${System.currentTimeMillis()}.png"
            File(dir, name).outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            var entries = mutable.value.unknown.filter { entry ->
                runCatching { File(dir, entry.file).exists() }.getOrDefault(false)
            }
            entries = entries + UnknownScreen(
                id = name.removeSuffix(".png"),
                file = name,
                at = System.currentTimeMillis(),
                context = context
            )
            while (entries.size > MAX_SCREENS) {
                val oldest = entries.minByOrNull { it.at }
                if (oldest == null) break
                runCatching { File(dir, oldest.file).delete() }
                entries = entries - oldest
            }
            update { it.copy(unknown = entries, stats = it.stats.copy(unknown = entries.size)) }
        }
        return mutable.value.unknown.lastOrNull()
    }

    fun markTaught(id: String) = update { file ->
        file.copy(
            unknown = file.unknown.map { entry ->
                if (entry.id == id) entry.copy(taught = true) else entry
            }
        )
    }

    fun deleteUnknownScreen(id: String) = update { file ->
        val entry = file.unknown.firstOrNull { it.id == id }
        if (entry != null) runCatching { File(dir, entry.file).delete() }
        file.copy(unknown = file.unknown.filter { it.id != id })
    }

    fun screenshotFile(entry: UnknownScreen): File = File(dir, entry.file)

    fun saveRule(rule: RecoveryRule) = update {
        it.copy(rules = it.rules.filterNot { existing -> existing.id == rule.id } + rule)
    }

    /** Applies an edit to one stored rule, leaving the others untouched. */
    fun updateRule(id: String, transform: (RecoveryRule) -> RecoveryRule) = update { file ->
        file.copy(rules = file.rules.map { rule -> if (rule.id == id) transform(rule) else rule })
    }

    fun setRuleEnabled(id: String, enabled: Boolean) =
        updateRule(id) { it.copy(enabled = enabled) }

    fun deleteRule(id: String) = update { file ->
        file.copy(rules = file.rules.filterNot { it.id == id })
    }

    /** Records a successful recognition so the UI can show when it last ran. */
    fun noteRuleMatch(id: String, at: Long = System.currentTimeMillis()) = updateRule(id) {
        it.copy(lastMatchedAt = at, matchCount = it.matchCount + 1)
    }

    fun userRules(): List<RecoveryRule> = mutable.value.rules

    fun hashDistanceFrom(bitmap: Bitmap): String = ScreenHash.fromBitmap(bitmap)

    companion object {
        private const val FILE_NAME = "recovery.json"
        private const val SCREEN_DIR = "unknown_screens"
        private const val MAX_SCREENS = 20
        private const val HOUR_MS = 60L * 60L * 1000L

        @Volatile
        private var instance: RecoveryRepository? = null

        fun get(context: Context): RecoveryRepository =
            instance ?: synchronized(this) {
                instance ?: RecoveryRepository(context.applicationContext).also { instance = it }
            }
    }
}
