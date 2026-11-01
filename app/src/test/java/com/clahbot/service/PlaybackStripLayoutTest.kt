package com.clahbot.service

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.clahbot.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStripLayoutTest {

    @Test
    fun playingControlsHaveBreathingRoom() {
        val context = ContextThemeWrapper(
            org.robolectric.RuntimeEnvironment.getApplication(),
            R.style.Theme_AutoClash
        )
        val root = LayoutInflater.from(context).inflate(R.layout.overlay_controller, null)
        val panel = root.findViewById<LinearLayout>(R.id.panel)
        val content = root.findViewById<FrameLayout>(R.id.contentFrame)
        val playing = root.findViewById<LinearLayout>(R.id.playingPanel)
        val pause = root.findViewById<ImageButton>(R.id.btnPlayPause)
        val stop = root.findViewById<ImageButton>(R.id.btnStopPlayback)
        val minimize = root.findViewById<ImageButton>(R.id.btnPlayMinimize)
        val timer = root.findViewById<TextView>(R.id.tvPlayElapsed)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        listOf(
            R.id.controllerHeader,
            R.id.readyFooter,
            R.id.playPill,
            R.id.saveBox,
            R.id.savedBox,
            R.id.discardedBox,
            R.id.recordingPanel,
            R.id.reviewPanel,
            R.id.readyPanel,
            R.id.statsPanel
        ).forEach { id -> root.findViewById<View>(id)?.visibility = View.GONE }
        panel.setPadding(0, 0, 0, 0)
        panel.background = null
        panel.layoutParams = FrameLayout.LayoutParams(dp(368), dp(52))
        content.visibility = View.VISIBLE
        (content.layoutParams as LinearLayout.LayoutParams).topMargin = 0
        playing.visibility = View.VISIBLE
        playing.layoutParams = playing.layoutParams.apply { height = LinearLayout.LayoutParams.MATCH_PARENT }

        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(368), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(52), View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, dp(368), dp(52))

        for (button in listOf(pause, stop, minimize)) {
            assertEquals("button must be 36 dp tall", dp(36), button.height)
            assertTrue("button touches the top edge: ${button.top}", button.top >= dp(6))
            assertTrue(
                "button touches the bottom edge: ${button.bottom}",
                button.bottom <= dp(52) - dp(6)
            )
        }
        assertTrue("pause/stop overlap", stop.left >= pause.right)
        assertTrue("stop/minimize overlap", minimize.left >= stop.right)
        assertTrue("controls escape the strip", minimize.right <= dp(368) - dp(6))
        assertTrue("timer is too narrow", timer.width >= dp(46))
        assertTrue("timer overlaps pause", pause.left >= timer.right)
    }
}
