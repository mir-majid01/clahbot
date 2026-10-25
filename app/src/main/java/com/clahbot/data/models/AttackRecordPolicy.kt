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
    fun mayCreateRecord(trigger: AttackTrigger): Boolean =
        trigger == AttackTrigger.DETECTED_RESULTS_SCREEN
}
