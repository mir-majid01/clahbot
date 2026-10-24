package com.clahbot.data.models

object LootInput {
    /** Blank means "not captured"; zero is a real value. */
    fun parse(raw: String): Long? {
        val normalized = raw.trim()
            .replace(",", "")
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace("_", "")
        if (normalized.isEmpty()) return null
        if (normalized.any { !it.isDigit() }) return null
        return normalized.toLongOrNull()?.takeIf { it >= 0L }
    }
}
