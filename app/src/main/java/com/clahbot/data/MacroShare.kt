package com.clahbot.data

import android.content.Context
import com.clahbot.data.models.Macro
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Versioned, single-macro share format. It carries only the macro data, never
 * the library, attacks or app settings.
 */
object MacroShare {

    const val FORMAT = "autoclash-macro"
    const val FORMAT_VERSION = 1
    const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

    private val gson = Gson()

    private data class Envelope(
        val format: String = FORMAT,
        val formatVersion: Int = FORMAT_VERSION,
        val appVersion: String,
        val exportedAt: Long,
        val macro: Macro
    )

    fun encode(macro: Macro, appVersion: String): String =
        gson.toJson(Envelope(appVersion = appVersion, exportedAt = System.currentTimeMillis(), macro = macro))

    /** Accepts our envelope or a bare [Macro] JSON; returns null for anything else. */
    fun decode(json: String): Macro? {
        if (json.isBlank() || json.length > MAX_IMPORT_BYTES) return null
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val objectRoot = root.asJsonObject
        val macroJson: JsonObject = if (objectRoot.has("format")) {
            val format = runCatching { objectRoot.get("format").asString }.getOrNull()
            if (format != FORMAT) return null
            val version = runCatching { objectRoot.get("formatVersion")?.asInt ?: 0 }.getOrDefault(0)
            if (version !in 1..FORMAT_VERSION) return null
            runCatching { objectRoot.getAsJsonObject("macro") }.getOrNull()?.takeIf { it.isJsonObject }
                ?: return null
        } else {
            objectRoot
        }
        val macro = runCatching { gson.fromJson(macroJson, Macro::class.java) }.getOrNull() ?: return null
        if (macro.gestures.isEmpty()) return null
        return sanitizeMacro(macro.copy(id = 0))
    }

    fun writeExport(context: Context, macro: Macro, appVersion: String): File {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = macro.name
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "macro" }
        val file = File(directory, "$safeName-${macro.id}.json")
        file.writeText(encode(macro, appVersion), Charsets.UTF_8)
        return file
    }
}
