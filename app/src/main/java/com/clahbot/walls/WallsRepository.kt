package com.clahbot.walls

import android.content.Context
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

    private val mutable = MutableStateFlow(load())
    val state: StateFlow<WallFile> = mutable

    private fun load(): WallFile = runCatching {
        if (file.exists()) decode(file.readText()) else WallFile()
    }.getOrNull() ?: WallFile()

    private fun decode(text: String): WallFile {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) return WallFile()
        val stored = root.asJsonObject.get("settings")
        if (stored == null || !stored.isJsonObject) return WallFile()
        val raw = stored.asJsonObject
        if (OBSOLETE_KEYS.any(raw::has)) return WallFile()

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

    private fun JsonElement?.isNameOf(names: List<String>): Boolean =
        this != null && isJsonPrimitive && asJsonPrimitive.isString && asString in names

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

    @Synchronized
    fun refresh(): WallFile {
        mutable.value = load()
        return mutable.value
    }

    @Synchronized
    private fun update(transform: (WallFile) -> WallFile) {
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
