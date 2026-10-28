package com.clahbot.results

import android.content.Context
import android.graphics.BitmapFactory
import com.clahbot.data.AttackRepository
import com.clahbot.data.models.AttackRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

object ResultsRescan {

    data class Summary(val scanned: Int, val updated: Int)

    /**
     * Re-reads saved screenshots of confirmed records that have no complete
     * loot. A record is updated only when the current consensus supplies all
     * three main values; the original screenshot is never replaced.
     */
    suspend fun run(context: Context, repository: AttackRepository): Summary =
        withContext(Dispatchers.IO) {
            val candidates = repository.refresh().filter { record ->
                record.confirmed &&
                    record.resultsScreenshot != null &&
                    (!record.hasLoot || !record.complete)
            }
            if (candidates.isEmpty()) return@withContext Summary(0, 0)

            val reader = AppResultsReader(context)
            var scanned = 0
            var updated = 0
            try {
                for (record in candidates) {
                    val path = record.resultsScreenshot ?: continue
                    val file = File(path)
                    if (!file.isFile) continue
                    val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                        ?: continue
                    try {
                        val result = withTimeoutOrNull(30_000) { reader.read(bitmap) }
                            ?: continue
                        scanned++
                        val read = result.read
                        if (!RescanPolicy.mayApply(
                                read.outcome,
                                read.gold,
                                read.elixir,
                                read.darkElixir
                            )
                        ) {
                            continue
                        }
                        val changed = repository.update(
                            record.copy(
                                gold = read.gold,
                                elixir = read.elixir,
                                darkElixir = read.darkElixir,
                                bonusGold = read.bonusGold ?: record.bonusGold,
                                bonusElixir = read.bonusElixir ?: record.bonusElixir,
                                bonusDarkElixir = read.bonusDarkElixir ?: record.bonusDarkElixir,
                                lootSource = AttackRecord.LOOT_OCR,
                                captureSource = AttackRecord.SOURCE_RESCAN,
                                outcome = read.outcome.name.lowercase(Locale.US),
                                verified = true,
                                complete = read.lootComplete,
                                note = record.note + " Re-scanned with the current reader."
                            )
                        )
                        if (changed != null) updated++
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                reader.closeAsync()
            }
            Summary(scanned, updated)
        }

    fun summaryText(summary: Summary): String = when {
        summary.scanned == 0 -> "No unreadable captures found"
        summary.updated == 0 -> "Re-scanned ${summary.scanned}; no record changed"
        else -> "Re-scanned ${summary.scanned}; updated ${summary.updated}"
    }
}