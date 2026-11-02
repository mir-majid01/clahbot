package com.clahbot.data

import java.util.Locale

object MacroNaming {
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
