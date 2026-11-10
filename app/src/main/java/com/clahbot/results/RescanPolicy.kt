package com.clahbot.results

import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.ResultsOutcome
import com.clahbot.ocr.ResultsRead
import com.clahbot.ocr.RowReading
import java.util.Locale

object RescanPolicy {
    fun mayApply(
        outcome: ResultsOutcome?,
        gold: Long?,
        elixir: Long?,
        darkElixir: Long?
    ): Boolean = outcome != null &&
        gold != null && gold in 0..LootSanity.MAX_GOLD &&
        elixir != null && elixir in 0..LootSanity.MAX_ELIXIR &&
        (darkElixir == null || darkElixir in 0..LootSanity.MAX_DARK)

    fun merge(record: AttackRecord, raw: ResultsRead, rows: List<RowReading> = emptyList()): AttackRecord? {
        val sanity = LootSanity.sanitize(raw)
        val read = sanity.read
        if (!mayApply(read.outcome, read.gold, read.elixir, read.darkElixir)) return null
        val outcome = read.outcome.name.lowercase(Locale.US)
        if (record.outcome != null && record.outcome != outcome) return null

        val bonusGold = read.bonusGold ?: record.bonusGold
        val bonusElixir = read.bonusElixir ?: record.bonusElixir
        val bonusDark = read.bonusDarkElixir ?: record.bonusDarkElixir
        val newBonusComplete = read.bonusGold != null && read.bonusElixir != null && read.bonusDarkElixir != null
        val oldBonusComplete = record.bonusState == AttackRecord.BONUS_CAPTURED &&
            record.bonusGold != null && record.bonusElixir != null && record.bonusDarkElixir != null
        val bonusState = when {
            newBonusComplete || oldBonusComplete -> AttackRecord.BONUS_CAPTURED
            read.bonusPanel || record.bonusState == AttackRecord.BONUS_NOT_CAPTURED ||
                record.bonusState == AttackRecord.BONUS_CAPTURED ||
                bonusGold != null || bonusElixir != null || bonusDark != null -> AttackRecord.BONUS_NOT_CAPTURED
            else -> record.bonusState
        }
        val reasons = sanity.reasons.toMutableList()
        reasons += read.issues.filterNot { issue ->
            when (issue) {
                "loot row 1: no OCR line in slot" -> read.gold != null
                "loot row 2: no OCR line in slot" -> read.elixir != null
                "loot row 3: no OCR line in slot" -> true
                "loot row 3: not captured (not_captured)",
                "loot row 3: not captured (unreadable)" -> read.darkElixir == null
                else -> false
            }
        }
        reasons += rows.filter { it.combined != null && it.status !in setOf("consensus", "balance_math") }
            .map { "${it.role.name.lowercase(Locale.US)} row ${it.index + 1}: ${it.status}" }
        if (read.needsReview && read.lootComplete && reasons.isEmpty()) {
            reasons += "The OCR reading still needs verification."
        }
        if (bonusState == AttackRecord.BONUS_NOT_CAPTURED) reasons += "Bonus missing or incomplete"
        val reviewReason = reasons.distinct().takeIf { it.isNotEmpty() }?.joinToString("; ")
        return record.copy(
            gold = read.gold,
            elixir = read.elixir,
            darkElixir = read.darkElixir ?: record.darkElixir,
            bonusGold = bonusGold,
            bonusElixir = bonusElixir,
            bonusDarkElixir = bonusDark,
            bonusState = bonusState,
            lootSource = AttackRecord.LOOT_OCR,
            captureSource = AttackRecord.SOURCE_RESCAN,
            outcome = outcome,
            verified = reviewReason == null,
            complete = read.mainLootComplete,
            reviewReason = reviewReason
        )
    }
}
