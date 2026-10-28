package com.clahbot.results

import android.content.Context
import android.graphics.BitmapFactory
import com.clahbot.data.AttackRepository
import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.ReaderStrategy
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
                    (!record.hasLoot || !record.complete || record.needsReview)
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
                        val result = withTimeoutOrNull(30_000) {
                            reader.read(bitmap, ReaderStrategy.ALL)
                        } ?: continue
                        scanned++
                        val sanity = LootSanity.sanitize(result.read)
                        val read = sanity.read
                        if (sanity.reasons.isNotEmpty()) continue
                        if (!RescanPolicy.mayApply(
                                read.outcome,
                                read.gold,
                                read.elixir,
                                read.darkElixir
                            )
                        ) {
                            continue
                        }
                        val bonusGold = read.bonusGold ?: record.bonusGold
                        val bonusElixir = read.bonusElixir ?: record.bonusElixir
                        val bonusDark = read.bonusDarkElixir ?: record.bonusDarkElixir
                        val bonusAny = bonusGold != null || bonusElixir != null || bonusDark != null
                        val bonusState = when {
                            read.bonusPanel && bonusAny -> AttackRecord.BONUS_CAPTURED
                            read.bonusPanel -> AttackRecord.BONUS_NOT_CAPTURED
                            record.bonusState == AttackRecord.BONUS_CAPTURED &&
                                bonusAny -> AttackRecord.BONUS_CAPTURED
                            else -> record.bonusState ?: AttackRecord.BONUS_NONE
                        }
                        val reviewReason = if (bonusState == AttackRecord.BONUS_NOT_CAPTURED) {
                            "Bonus panel present but not captured"
                        } else {
                            null
                        }
                        val changed = repository.update(
                            record.copy(
                                gold = read.gold,
                                elixir = read.elixir,
                                darkElixir = read.darkElixir,
                                bonusGold = bonusGold,
                                bonusElixir = bonusElixir,
                                bonusDarkElixir = bonusDark,
                                bonusState = bonusState,
                                lootSource = AttackRecord.LOOT_OCR,
                                captureSource = AttackRecord.SOURCE_RESCAN,
                                outcome = read.outcome.name.lowercase(Locale.US),
                                verified = read.lootComplete && reviewReason == null,
                                complete = read.lootComplete,
                                reviewReason = reviewReason,
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