package com.clahbot.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/main/baseline-prof.txt. Run once per release on the
 * physical device:
 *
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.clahbot.benchmark.BaselineProfileGenerator
 *
 * The generated file is picked up by the release build through profileinstaller.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun generateDashboardAndSettingsProfile() {
        rule.collect(
            packageName = "com.clahbot",
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg("com.clahbot")), 8_000)
            val width = device.displayWidth
            val height = device.displayHeight
            repeat(4) {
                device.swipe(
                    width / 2,
                    (height * 0.78f).toInt(),
                    width / 2,
                    (height * 0.28f).toInt(),
                    25
                )
            }
            repeat(2) {
                device.swipe(
                    width / 2,
                    (height * 0.28f).toInt(),
                    width / 2,
                    (height * 0.78f).toInt(),
                    25
                )
            }
            val gear = device.wait(Until.findObject(By.desc("Settings")), 4_000)
            if (gear != null) {
                gear.click()
                device.waitForIdle()
                repeat(5) {
                    device.swipe(
                        width / 2,
                        (height * 0.78f).toInt(),
                        width / 2,
                        (height * 0.28f).toInt(),
                        25
                    )
                }
                device.pressBack()
                device.waitForIdle()
            }
        }
    }
}
