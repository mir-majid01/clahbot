package com.clahbot.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark scroll tests for the dashboard and Settings.
 *
 * Run on a physical device with:
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest
 * or a single test:
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.clahbot.benchmark.ScrollBenchmark
 *
 * Frame stats are also asserted by FrameThresholdTest, which fails the build
 * when janky frames are >=2% or the 95th percentile frame time is >=10 ms.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun scrollUpAndDown() {
        val width = device.displayWidth
        val height = device.displayHeight
        repeat(5) {
            device.swipe(width / 2, (height * 0.78f).toInt(), width / 2, (height * 0.28f).toInt(), 30)
        }
        repeat(5) {
            device.swipe(width / 2, (height * 0.28f).toInt(), width / 2, (height * 0.78f).toInt(), 30)
        }
    }

    @Test
    fun dashboardScroll() = benchmarkRule.measureRepeated(
        packageName = "com.clahbot",
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg("com.clahbot")), 5_000)
        },
        measureBlock = { scrollUpAndDown() }
    )

    @Test
    fun settingsScroll() = benchmarkRule.measureRepeated(
        packageName = "com.clahbot",
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg("com.clahbot")), 5_000)
            val gear = device.findObject(By.desc("Settings"))
            if (gear != null) {
                gear.click()
                device.waitForIdle()
            }
        },
        measureBlock = { scrollUpAndDown() }
    )
}
