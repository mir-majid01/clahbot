package com.clahbot.service

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.EditText
import com.clahbot.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingNameLayoutTest {

    @Test
    fun anOverlongNameIsVisibleInsteadOfBeingSilentlyTruncated() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_AutoClash
        )
        val root = LayoutInflater.from(context).inflate(R.layout.overlay_controller, null)
        val input = root.findViewById<EditText>(R.id.etMacroName)
        val entered = "x".repeat(41)

        input.setText(entered)

        assertEquals(entered, input.text.toString())
    }
}