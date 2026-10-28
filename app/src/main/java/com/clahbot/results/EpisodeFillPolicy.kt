package com.clahbot.results

import com.clahbot.data.models.AttackRecord
import com.clahbot.ocr.ResultsRead

/**
 * Later reads of one still-open result screen may only fill fields the saved
 * record is missing. An existing non-null value is never replaced; a
 * disagreement is surfaced as a review reason instead.
 */
object EpisodeFillPolicy {

    data class Filled(val record: AttackRecord, val changed: Boolean, val conflicts: List<String>)

    fun fill(existing: AttackRecord, read: ResultsRead): Filled {
        val sanity = LootSanity.sanitize(read)
        val clean = sanity.read
        val reasons = sanity.reasons.toMutableList()

        fun pick(current: Long?, incoming: Long?, label: String): Long? {
            if (current != null && incoming != null && current != incoming) {
                reasons += "$label changed from $current to $incoming on a later read"
            }
            return current ?: incoming
        }

        val gold = pick(existing.gold, clean.gold, "Gold")
        val elixir = pick(existing.elixir, clean.elixir, "Elixir")
        val dark = pick(existing.darkElixir, clean.darkElixir, "Dark elixir")
        val bonusGold = pick(existing.bonusGold, clean.bonusGold, "Bonus gold")
        val bonusElixir = pick(existing.bonusElixir, clean.bonusElixir, "Bonus elixir")
        val bonusDark = pick(
            existing.bonusDarkElixir,
            clean.bonusDarkElixir,
            "Bonus dark elixir"
        )
        val bonusAny = bonusGold != null || bonusElixir != null || bonusDark != null
        val bonusState = when {
            clean.bonusPanel && bonusAny -> AttackRecord.BONUS_CAPTURED
            clean.bonusPanel -> AttackRecord.BONUS_NOT_CAPTURED
            existing.bonusState == AttackRecord.BONUS_CAPTURED -> AttackRecord.BONUS_CAPTURED
            else -> existing.bonusState ?: AttackRecord.BONUS_NONE
        }
        val complete = gold != null && elixir != null && dark != null
        val previousAutomaticReason = existing.reviewReason?.takeIf {
            it.contains("not captured", ignoreCase = true) ||
                it.contains("did not stabilize", ignoreCase = true)
        }
        val reviewReason = reasons
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
            .ifBlank {
                if (complete) null else previousAutomaticReason ?: "Some main loot fields were not captured"
            }
        val filledSomething = gold != existing.gold ||
            elixir != existing.elixir ||
            dark != existing.darkElixir ||
            bonusGold != existing.bonusGold ||
            bonusElixir != existing.bonusElixir ||
            bonusDark != existing.bonusDarkElixir
        val changed = filledSomething ||
            bonusState != existing.bonusState ||
            reviewReason != existing.reviewReason ||
            complete != existing.complete
        val updated = existing.copy(
            gold = gold,
            elixir = elixir,
            darkElixir = dark,
            bonusGold = bonusGold,
            bonusElixir = bonusElixir,
            bonusDarkElixir = bonusDark,
            bonusState = bonusState,
            complete = complete,
            verified = if (filledSomething) complete && reviewReason == null else existing.verified,
            reviewReason = reviewReason,
            lootSource = when {
                existing.hasLoot -> existing.lootSource
                gold != null || elixir != null || dark != null -> AttackRecord.LOOT_OCR
                else -> existing.lootSource
            },
            note = if (changed) {
                existing.note + " Later reads filled missing fields."
            } else {
                existing.note
            }
        )
        return Filled(updated, changed, reasons)
    }
}
