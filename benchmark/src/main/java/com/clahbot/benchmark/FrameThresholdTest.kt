package com.clahbot.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device scroll gate. The Macrobenchmark above produces the frame-timing
 * report; this test reads the same gfxinfo counters and fails when the accepted
 * budget is exceeded: janky frames < 2% and p95 frame time < 10 ms.
 */
@RunWith(AndroidJUnit4::class)
class FrameThresholdTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private data class Stats(val jankPercent: Double, val p95Ms: Int, val raw: String)

    private fun readStats(): Stats {
        val raw = device.executeShellCommand("dumpsys gfxinfo com.clahbot framestats")
        val jank = Regex("Janky frames: \\d+ \\(([0-9.]+)%\\)").find(raw)
        val p95 = Regex("95th percentile: (\\d+)ms").find(raw)
        if (jank == null || p95 == null) {
            fail("Could not read frame stats:\n${raw.take(2_000)}")
            return Stats(100.0, 1000, raw)
        }
        return Stats(jank.groupValues[1].toDouble(), p95.groupValues[1].toInt(), raw)
    }

    private fun launchApp() {
        device.pressHome()
        device.executeShellCommand(
            "monkey -p com.clahbot -c android.intent.category.LAUNCHER 1"
        )
        device.wait(Until.hasObject(By.pkg("com.clahbot")), 8_000)
        device.waitForIdle()
    }

    private fun scroll() {
        val width = device.displayWidth
        val height = device.displayHeight
        repeat(6) {
            device.swipe(
                width / 2,
                (height * 0.78f).toInt(),
                width / 2,
                (height * 0.28f).toInt(),
                25
            )
        }
        repeat(4) {
            device.swipe(
                width / 2,
                (height * 0.28f).toInt(),
                width / 2,
                (height * 0.78f).toInt(),
                25
            )
        }
        device.waitForIdle()
    }

    private fun assertBudget(label: String) {
        val stats = readStats()
        assertTrue(
            "$label jank ${stats.jankPercent}% exceeded the 2% budget",
            stats.jankPercent < 2.0
        )
        assertTrue(
            "$label p95 ${stats.p95Ms} ms exceeded the 10 ms budget",
            stats.p95Ms < 10
        )
    }

    @Test
    fun dashboardScrollMeetsFrameBudget() {
        launchApp()
        device.executeShellCommand("dumpsys gfxinfo com.clahbot reset")
        scroll()
        assertBudget("Dashboard")
    }

    @Test
    fun settingsScrollMeetsFrameBudget() {
        launchApp()
        val gear = device.wait(Until.findObject(By.desc("Settings")), 5_000)
        if (gear == null) {
            fail("Settings gear not found")
            return
        }
        gear.click()
        device.waitForIdle()
        device.executeShellCommand("dumpsys gfxinfo com.clahbot reset")
        scroll()
        assertBudget("Settings")
    }
}
