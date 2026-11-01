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

    data class Summary(val scanned: Int, val updated: Int, val skippedNoFrame: Int)

    fun candidates(records: List<AttackRecord>): List<AttackRecord> = records.filter { record ->
        record.confirmed &&
            record.resultsScreenshot != null &&
            (!record.hasLoot || !record.complete || record.needsReview)
    }

    /**
     * Re-reads saved screenshots of confirmed records that are incomplete or
     * carry a review reason. Only a complete, plausible three-value read is
     * applied; the original screenshot is never replaced.
     */
    suspend fun run(context: Context, repository: AttackRepository): Summary =
        withContext(Dispatchers.IO) {
            val records = repository.refresh()
            val candidates = candidates(records)
            if (candidates.isEmpty()) {
                return@withContext Summary(0, 0, records.count { it.confirmed && !it.hasLoot })
            }

            val reader = AppResultsReader(context)
            var scanned = 0
            var updated = 0
            var skippedNoFrame = 0
            try {
                for (record in candidates) {
                    val path = record.resultsScreenshot
                    if (path == null) {
                        skippedNoFrame++
                        continue
                    }
                    val file = File(path)
                    if (!file.isFile) {
                        skippedNoFrame++
                        continue
                    }
                    val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                    if (bitmap == null) {
                        skippedNoFrame++
                        continue
                    }
                    try {
                        val result = withTimeoutOrNull(30_000) {
                            reader.read(bitmap, ReaderStrategy.ALL)
                        } ?: continue
                        scanned++
                        val sanity = LootSanity.sanitize(result.read)
                        val read = sanity.read
                        if (!RescanPolicy.mayApply(
                                read.outcome,
                                read.gold,
                                read.elixir,
                                read.darkElixir
                            )
                        ) {
                            continue
                        }
                        val bonusAny = read.bonusGold != null ||
                            read.bonusElixir != null ||
                            read.bonusDarkElixir != null
                        val bonusGold = read.bonusGold ?: record.bonusGold
                        val bonusElixir = read.bonusElixir ?: record.bonusElixir
                        val bonusDark = read.bonusDarkElixir ?: record.bonusDarkElixir
                        val resolvedBonus = bonusGold != null ||
                            bonusElixir != null ||
                            bonusDark != null
                        val bonusState = when {
                            resolvedBonus -> AttackRecord.BONUS_CAPTURED
                            read.bonusPanel -> record.bonusState
                                ?.takeIf { it != AttackRecord.BONUS_CAPTURED }
                                ?: AttackRecord.BONUS_NONE
                            else -> AttackRecord.BONUS_NONE
                        }
                        val reviewReason = when {
                            sanity.reasons.isNotEmpty() ->
                                sanity.reasons.joinToString("; ")

                            bonusState == AttackRecord.BONUS_NOT_CAPTURED ->
                                "Bonus missing"

                            else -> null
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
                                verified = reviewReason == null,
                                complete = read.lootComplete,
                                reviewReason = reviewReason,
                                note = record.note.trimEnd() +
                                    " Re-scanned with the current reader."
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
            Summary(scanned, updated, skippedNoFrame)
        }

    fun summaryText(summary: Summary): String = when {
        summary.scanned == 0 && summary.skippedNoFrame == 0 ->
            "No attacks need review"

        summary.scanned == 0 ->
            "No saved frame to re-scan (" +
                com.clahbot.util.Plural.of(summary.skippedNoFrame, "record") +
                " ${if (summary.skippedNoFrame == 1) "has" else "have"} none)"

        summary.updated == 0 -> "Re-scanned ${summary.scanned}; no record changed"
        else -> "Re-scanned ${summary.scanned}; updated ${summary.updated}"
    }
}
