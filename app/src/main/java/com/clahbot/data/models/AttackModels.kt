package com.clahbot.data.models

/**
 * One attack session. Loot fields are nullable on purpose: `null` means "not
 * observed", which is different from zero. The app never invents a value.
 */
data class AttackRecord(
    val id: Long = 0,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val durationMs: Long = 0,
    val macroId: Long = -1,
    val macroName: String = "",
    val speedMultiplier: Float = 1f,
    val repeatMode: String = "once",
    val gold: Long? = null,
    val elixir: Long? = null,
    val darkElixir: Long? = null,
    /** Loot from the separate bonus panel, never folded into the values above. */
    val bonusGold: Long? = null,
    val bonusElixir: Long? = null,
    val bonusDarkElixir: Long? = null,
    /** "none" (never observed), "manual" (entered by the user) or "ocr". */
    val lootSource: String = LOOT_NONE,
    /** How this record's screen was turned into a record. */
    val captureSource: String? = null,
    /** Cheap whole-screen hash used to avoid recording one screen twice. */
    val screenHash: String? = null,
    /** "unknown", "completed", or "interrupted". */
    val result: String = RESULT_UNKNOWN,
    /** "victory" or "defeat" when the result screen was read automatically. */
    val outcome: String? = null,
    /** Local PNG of the result screen, when screenshot saving was enabled. */
    val resultsScreenshot: String? = null,
    /** JSON sidecar with the app's own read, next to [resultsScreenshot]. */
    val resultsSidecar: String? = null,
    val verified: Boolean = false,
    val complete: Boolean = true,
    /**
     * A playback session is not an attack. Only a detected results screen
     * creates a confirmed attack record.
     */
    val confirmed: Boolean = false,
    val note: String = ""
) {
    val hasLoot: Boolean get() = gold != null || elixir != null || darkElixir != null
    val hasBonus: Boolean get() = bonusGold != null || bonusElixir != null || bonusDarkElixir != null

    companion object {
        const val LOOT_NONE = "none"
        const val LOOT_MANUAL = "manual"
        const val LOOT_OCR = "ocr"
        const val SOURCE_AUTO = "auto-read"
        const val SOURCE_RESCAN = "re-scanned"
        const val SOURCE_MANUAL = "manual-corrected"
        const val RESULT_UNKNOWN = "unknown"
        const val RESULT_COMPLETED = "completed"
        const val RESULT_INTERRUPTED = "interrupted"
    }
}

data class AttackSummary(
    val count: Int = 0,
    val lootedCount: Int = 0,
    val gold: Long = 0,
    val elixir: Long = 0,
    val darkElixir: Long = 0,
    val totalDurationMs: Long = 0,
    val averageDurationMs: Long = 0
)

object AttackStats {

    fun confirmedOnly(records: List<AttackRecord>): List<AttackRecord> =
        records.filter { it.confirmed }

    fun summarize(records: List<AttackRecord>): AttackSummary {
        if (records.isEmpty()) return AttackSummary()
        var gold = 0L
        var elixir = 0L
        var dark = 0L
        var looted = 0
        var duration = 0L
        for (r in records) {
            if (r.gold != null || r.elixir != null || r.darkElixir != null) looted++
            r.gold?.let { gold += it }
            r.elixir?.let { elixir += it }
            r.darkElixir?.let { dark += it }
            duration += r.durationMs.coerceAtLeast(0L)
        }
        return AttackSummary(
            count = records.size,
            lootedCount = looted,
            gold = gold,
            elixir = elixir,
            darkElixir = dark,
            totalDurationMs = duration,
            averageDurationMs = duration / records.size
        )
    }

    /** Newest first, optionally limited to records started after [since]. */
    fun filter(records: List<AttackRecord>, since: Long?): List<AttackRecord> {
        val filtered = if (since == null) records else records.filter { it.startedAt >= since }
        return filtered.sortedByDescending { it.startedAt }
    }

    fun dailyTotals(records: List<AttackRecord>, days: Int, now: Long = System.currentTimeMillis()): List<Pair<Long, Long>> {
        if (days <= 0) return emptyList()
        val dayMs = 24L * 60L * 60L * 1000L
        val todayStart = now - (now % dayMs)
        return (days - 1 downTo 0).map { offset ->
            val start = todayStart - offset * dayMs
            val end = start + dayMs
            val total = records
                .filter { it.startedAt in start until end }
                .sumOf { (it.gold ?: 0L) + (it.elixir ?: 0L) + (it.darkElixir ?: 0L) }
            start to total
        }
    }
}
