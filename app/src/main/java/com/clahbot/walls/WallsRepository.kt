package com.clahbot.walls

import android.content.Context
import com.clahbot.data.AppEventLog
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The persisted file. Releases before 2.12 stored a drawn wall line, a camera
 * landmark, per-resource costs and caps here. None of that survives, so an old
 * file is ignored wholesale rather than half-migrated, and a malformed or
 * truncated one falls back to defaults instead of crashing.
 */
data class WallFile(
    @SerializedName("settings") val settings: WallSettings = WallSettings()
)

class WallsRepository internal constructor(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    private val gson = Gson()

    /** Non-null after a failed save, cleared on the next successful one. */
    @Volatile
    var writeError: String? = null
        private set

    private val mutable = MutableStateFlow(load())
    val state: StateFlow<WallFile> = mutable

    private fun load(): WallFile {
        if (!file.exists()) {
            AppEventLog.log("walls: no file yet, using defaults")
            return WallFile()
        }
        return runCatching { decode(file.readText()) }
            .getOrElse { throwable ->
                AppEventLog.log("walls: file unreadable (${throwable.message}); using defaults")
                // Preserve the original aside before falling back to defaults, so the
                // next save cannot clobber recoverable configuration and history.
                quarantine()
                WallFile()
            }
            .also {
                val s = it.settings
                AppEventLog.log(
                    "walls: loaded enabled=${s.enabled} macro=${s.wallMacroId} " +
                        "cost=${s.costPerWall} reserve=${s.minReserve} tested=${s.testedWallMacroId}"
                )
            }
    }

    private fun decode(text: String): WallFile {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) {
            AppEventLog.log("walls: file is not an object; using defaults")
            return WallFile()
        }
        val stored = root.asJsonObject.get("settings")
        if (stored == null || !stored.isJsonObject) {
            AppEventLog.log("walls: file has no settings object; using defaults")
            return WallFile()
        }
        val raw = stored.asJsonObject
        if (OBSOLETE_KEYS.any(raw::has)) return WallFile(migrateLegacy(raw))

        // Serializing the Kotlin default keeps every absent field at its real
        // default: Gson would otherwise leave nulls where the model is non-null.
        val settings = gson.toJsonTree(WallSettings()).asJsonObject
        SETTINGS_KEYS.forEach { key ->
            val value = raw.get(key) ?: return@forEach
            when (key) {
                "resourceMode" -> if (value.isNameOf(WallResourceMode.entries.map { it.name })) {
                    settings.add(key, value)
                }

                "priority" -> if (value.isNameOf(WallPriority.entries.map { it.name })) {
                    settings.add(key, value)
                }

                "history" -> settings.add(key, validHistory(value))

                else -> settings.add(key, value)
            }
        }
        return WallFile(gson.fromJson(settings, WallSettings::class.java))
    }

    /**
     * A walls.json from the wall-line release still holds the switch, the
     * priority and the price the user typed. Those carry over; the drawn line,
     * the landmark, the caps and the per-resource split do not — the new system
     * has nothing to use them for, and guessing would be worse than resetting.
     */
    private fun migrateLegacy(raw: JsonObject): WallSettings {
        val gold = raw.number("goldCost") ?: 0L
        val elixir = raw.number("elixirCost") ?: 0L
        val cost = if (gold > 0L) gold else elixir
        val reserve = maxOf(raw.number("reserveGold") ?: 0L, raw.number("reserveElixir") ?: 0L)
        val settings = WallSettings(
            enabled = raw.flag("enabled") ?: false,
            priority = if (raw.string("priority") == WallPriority.ELIXIR_FIRST.name) {
                WallPriority.ELIXIR_FIRST
            } else {
                WallPriority.GOLD_FIRST
            },
            costPerWall = cost,
            minReserve = reserve
        )
        AppEventLog.log(
            "walls: migrated wall-line file enabled=${settings.enabled} " +
                "cost=${settings.costPerWall} reserve=${settings.minReserve}"
        )
        return settings
    }

    private fun JsonElement?.isNameOf(names: List<String>): Boolean =
        this != null && isJsonPrimitive && asJsonPrimitive.isString && asString in names

    private fun JsonObject.flag(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    /** Entries from another era are dropped rather than rendered as blanks. */
    private fun validHistory(element: JsonElement?): JsonArray {
        val kept = JsonArray()
        element?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.isRunLog() }
            ?.forEach { entry ->
                kept.add(JsonObject().apply {
                    addProperty("at", entry.number("at"))
                    addProperty("macroName", entry.string("macroName"))
                    addProperty("resource", entry.string("resource"))
                    BALANCE_KEYS.forEach { key -> entry.number(key)?.let { addProperty(key, it) } }
                    addProperty("status", entry.string("status"))
                    addProperty("detail", entry.string("detail") ?: "")
                })
            }
        return kept
    }

    private fun JsonObject.isRunLog(): Boolean =
        number("at") != null &&
            string("macroName") != null &&
            string("status") != null &&
            string("resource") in WallTrigger.entries.map { it.name }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.number(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun quarantine() {
        try {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
        } catch (_: Exception) {
            // An unreadable file is treated as defaults; nothing else to preserve.
        }
    }

    @Synchronized
    private fun update(transform: (WallFile) -> WallFile) {
        val next = transform(mutable.value)
        writeError = null
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, FILE_NAME + ".tmp")
            tmp.writeText(gson.toJson(next))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(next))
                tmp.delete()
            }
            if (!file.exists() || file.length() <= 0L) {
                error("walls.json is empty after the write")
            }
        } catch (throwable: Throwable) {
            // The screen still shows what the user chose, but it must not pretend
            // the choice survived: the status line says why the next start resets.
            writeError = throwable.message ?: throwable.javaClass.simpleName
            AppEventLog.log("walls: WRITE FAILED $writeError")
        }
        mutable.value = next
    }

    fun updateSettings(transform: (WallSettings) -> WallSettings) =
        update { it.copy(settings = transform(it.settings)) }

    fun settings(): WallSettings = mutable.value.settings

    fun recordRun(log: WallRunLog) = update {
        it.copy(
            settings = it.settings.copy(history = (it.settings.history + log).takeLast(HISTORY_MAX))
        )
    }

    fun clearHistory() = update { it.copy(settings = it.settings.copy(history = emptyList())) }

    companion object {
        private const val FILE_NAME = "walls.json"
        private const val HISTORY_MAX = 50

        /** Everything the current model persists; anything else is ignored. */
        private val SETTINGS_KEYS = listOf(
            "enabled", "wallMacroId", "resourceMode", "priority", "costPerWall",
            "minReserve", "allowRepeatUpgrades", "testedWallMacroId", "history"
        )

        private val BALANCE_KEYS = listOf(
            "goldBefore", "goldAfter", "elixirBefore", "elixirAfter"
        )

        /** Keys only the removed wall-line system ever wrote. */
        private val OBSOLETE_KEYS = listOf(
            "line", "camera", "cameraMacroId", "goldCost", "elixirCost", "goldEnabled",
            "elixirEnabled", "reserveGold", "reserveElixir", "thresholdGold",
            "thresholdElixir", "maxPerCycle", "wallLevel", "capSession", "capDay"
        )

        @Volatile
        private var instance: WallsRepository? = null

        fun get(context: Context): WallsRepository =
            instance ?: synchronized(this) {
                instance ?: WallsRepository(context.applicationContext).also { instance = it }
            }
    }
}
