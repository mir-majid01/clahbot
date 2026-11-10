package com.clahbot.data

import java.util.Locale

object MacroNaming {
    const val MAX_NAME_LENGTH = 40

    fun validationError(name: String, existingNames: Collection<String>): String? {
        val trimmed = name.trim()
        val normalized = trimmed.lowercase(Locale.US)
        return when {
            trimmed.isEmpty() -> "Enter a macro name"
            trimmed.length > MAX_NAME_LENGTH -> "Use at most $MAX_NAME_LENGTH characters"
            existingNames.any { it.trim().lowercase(Locale.US) == normalized } ->
                "A macro named \"$trimmed\" already exists"
            else -> null
        }
    }

    fun uniqueDefault(base: String, existingNames: Collection<String>): String {
        val taken = existingNames.mapTo(HashSet()) { it.trim().lowercase(Locale.US) }
        if (base.lowercase(Locale.US) !in taken) return base
        var suffix = 2
        while (true) {
            val candidate = "$base $suffix"
            if (candidate.lowercase(Locale.US) !in taken) return candidate
            suffix++
        }
    }
}
