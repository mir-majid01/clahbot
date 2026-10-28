package com.clahbot.data

import com.clahbot.data.models.AttackRecord

object CaptureSource {

    fun label(value: String?, lootSource: String? = null): String = when (value) {
        AttackRecord.SOURCE_AUTO -> "auto-read"
        AttackRecord.SOURCE_RESCAN -> "re-scanned"
        AttackRecord.SOURCE_MANUAL -> "manually corrected"
        else -> when (lootSource) {
            AttackRecord.LOOT_OCR -> "auto-read"
            AttackRecord.LOOT_MANUAL -> "manually corrected"
            else -> "not captured"
        }
    }
}
