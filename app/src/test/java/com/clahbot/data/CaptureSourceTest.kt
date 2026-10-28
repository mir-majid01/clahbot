package com.clahbot.data

import com.clahbot.data.models.AttackRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureSourceTest {

    @Test
    fun labelsExposeHowTheRecordWasProduced() {
        assertEquals("auto-read", CaptureSource.label(AttackRecord.SOURCE_AUTO))
        assertEquals("re-scanned", CaptureSource.label(AttackRecord.SOURCE_RESCAN))
        assertEquals("manually corrected", CaptureSource.label(AttackRecord.SOURCE_MANUAL))
    }

    @Test
    fun legacyRecordsUseTheirLootSourceAsTheFallbackLabel() {
        assertEquals("auto-read", CaptureSource.label(null, AttackRecord.LOOT_OCR))
        assertEquals("manually corrected", CaptureSource.label(null, AttackRecord.LOOT_MANUAL))
        assertEquals("not captured", CaptureSource.label(null, AttackRecord.LOOT_NONE))
    }
}
