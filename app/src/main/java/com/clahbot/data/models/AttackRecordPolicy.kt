package com.clahbot.data.models

/**
 * The only permitted source of a new attack record. A playback finishing is
 * not evidence of an attack; a results screen is. Keeping the rule here makes
 * it testable and prevents it drifting back into the playback path.
 */
enum class AttackTrigger {
    PLAYBACK_COMPLETED,
    DETECTED_RESULTS_SCREEN
}

object AttackRecordPolicy {
    fun correctLoot(
        record: AttackRecord,
        gold: Long?, elixir: Long?, dark: Long?,
        bonusGold: Long?, bonusElixir: Long?, bonusDark: Long?,
        noBonus: Boolean
    ): AttackRecord {
        require(listOf(gold, elixir, dark, bonusGold, bonusElixir, bonusDark).all { it == null || it >= 0L })
        val mainComplete = gold != null && elixir != null
        val bonusComplete = bonusGold != null && bonusElixir != null && bonusDark != null
        val bonusState = when {
            noBonus -> AttackRecord.BONUS_NONE
            bonusComplete -> AttackRecord.BONUS_CAPTURED
            bonusGold != null || bonusElixir != null || bonusDark != null ||
                record.bonusState == AttackRecord.BONUS_NOT_CAPTURED ||
                record.bonusState == AttackRecord.BONUS_CAPTURED -> AttackRecord.BONUS_NOT_CAPTURED
            else -> record.bonusState
        }
        val reason = when {
            !mainComplete -> "Gold or elixir could not be read from the result screen."
            bonusState == AttackRecord.BONUS_NOT_CAPTURED -> "Bonus missing or incomplete"
            else -> null
        }
        val anyLoot = listOf(gold, elixir, dark).any { it != null } || (!noBonus &&
            listOf(bonusGold, bonusElixir, bonusDark).any { it != null })
        return record.copy(
            gold = gold, elixir = elixir, darkElixir = dark,
            bonusGold = if (noBonus) null else bonusGold,
            bonusElixir = if (noBonus) null else bonusElixir,
            bonusDarkElixir = if (noBonus) null else bonusDark,
            bonusState = bonusState,
            lootSource = if (anyLoot) AttackRecord.LOOT_MANUAL else AttackRecord.LOOT_NONE,
            captureSource = AttackRecord.SOURCE_MANUAL,
            confirmed = true,
            complete = mainComplete,
            verified = reason == null,
            reviewReason = reason
        )
    }

    fun mayCreateRecord(trigger: AttackTrigger): Boolean =
        trigger == AttackTrigger.DETECTED_RESULTS_SCREEN
}
