package com.clahbot.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private fun store(prefs: SharedPreferences): SettingsStore =
        ReflectionHelpers.callConstructor(
            SettingsStore::class.java,
            ReflectionHelpers.ClassParameter.from(SharedPreferences::class.java, prefs)
        )

    @Test
    fun aChangedSettingSurvivesARelaunch() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("settings_store_test", Context.MODE_PRIVATE)

        val first = store(prefs)
        assertTrue("haptics default on", first.settings.value.hapticFeedback)

        first.update { it.copy(hapticFeedback = false) }
        assertFalse(first.settings.value.hapticFeedback)

        // A fresh instance over the same preferences is what a relaunch reads.
        val relaunched = store(prefs)
        assertFalse(
            "a changed setting has to survive closing and reopening the app",
            relaunched.settings.value.hapticFeedback
        )

        relaunched.update { it.copy(hapticFeedback = true) }
        assertTrue(store(prefs).settings.value.hapticFeedback)
    }
}
