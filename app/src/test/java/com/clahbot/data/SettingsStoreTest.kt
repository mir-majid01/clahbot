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
    fun wallsCardExpansionDefaultsCollapsedAndSurvivesARelaunch() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("walls_card_test", Context.MODE_PRIVATE)

        val first = store(prefs)
        assertFalse("the card must start collapsed", first.settings.value.wallsCardExpanded)

        first.update { it.copy(wallsCardExpanded = true) }
        assertTrue(first.settings.value.wallsCardExpanded)

        // A fresh instance over the same preferences is what a relaunch reads.
        val relaunched = store(prefs)
        assertTrue(
            "expanding the card has to survive closing and reopening the app",
            relaunched.settings.value.wallsCardExpanded
        )

        relaunched.update { it.copy(wallsCardExpanded = false) }
        assertFalse(store(prefs).settings.value.wallsCardExpanded)
    }
}