package com.clahbot.execution

import com.clahbot.data.models.AutomationMode
import com.clahbot.data.models.BotConfig
import com.clahbot.data.models.GameScreen
import com.clahbot.data.models.VisionSnapshot
import com.clahbot.vision.OcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StateMachineLogicTest {

    private val ocr = OcrEngine()

    @Test
    fun parseLootValue_handlesKsuffix() {
        assertEquals(150_000L, ocr.parseLootValue("150K"))
    }

    @Test
    fun parseLootValue_handlesMsuffix() {
        assertEquals(2_000_000L, ocr.parseLootValue("2M"))
    }

    @Test
    fun parseLootValue_returnsZeroForNull() {
        assertEquals(0L, ocr.parseLootValue(null))
    }

    @Test
    fun botConfig_defaultsToSearchOnly() {
        val config = BotConfig()
        assertEquals(AutomationMode.SEARCH_ONLY, config.mode)
        assertTrue(config.autoPauseOnWarning)
    }

    @Test
    fun visionSnapshot_defaultsToUnknown() {
        val snapshot = VisionSnapshot()
        assertEquals(GameScreen.UNKNOWN, snapshot.gameScreen)
    }
}
