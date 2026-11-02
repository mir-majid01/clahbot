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

    private class Strip(val root: View, val density: Float) {
        val name = root.findViewById<TextView>(R.id.tvPlayingName)
        val timer = root.findViewById<TextView>(R.id.tvPlayElapsed)
        val run = root.findViewById<TextView>(R.id.tvPlayingRun)
        val pause = root.findViewById<ImageButton>(R.id.btnPlayPause)
        val stop = root.findViewById<ImageButton>(R.id.btnStopPlayback)
        val minimize = root.findViewById<ImageButton>(R.id.btnPlayMinimize)

        fun dp(value: Int): Int = (value * density).toInt()
    }

    private fun View.absLeft(): Int {
        var x = 0
        var view: View = this
        while (view.parent is View) {
            x += view.left
            view = view.parent as View
        }
        return x
    }

    private fun View.absTop(): Int {
        var y = 0
        var view: View = this
        while (view.parent is View) {
            y += view.top
            view = view.parent as View
        }
        return y
    }

    private fun layoutStrip(widthDp: Int): Strip {
        val context = ContextThemeWrapper(
            org.robolectric.RuntimeEnvironment.getApplication(),
            R.style.Theme_AutoClash
        )
        val root = LayoutInflater.from(context).inflate(R.layout.overlay_controller, null)
        val panel = root.findViewById<LinearLayout>(R.id.panel)
        val content = root.findViewById<FrameLayout>(R.id.contentFrame)
        val playing = root.findViewById<LinearLayout>(R.id.playingPanel)
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
        panel.layoutParams = FrameLayout.LayoutParams(dp(widthDp), dp(52))
        content.visibility = View.VISIBLE
        (content.layoutParams as LinearLayout.LayoutParams).topMargin = 0
        playing.visibility = View.VISIBLE
        playing.layoutParams = playing.layoutParams.apply { height = LinearLayout.LayoutParams.MATCH_PARENT }

        val strip = Strip(root, density)
        strip.name.text = "Macro Nov 02 14:21"
        strip.run.visibility = View.VISIBLE
        strip.run.text = "Run 1/3"

        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(52), View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, dp(widthDp), dp(52))
        return strip
    }

    @Test
    fun playingControlsHaveBreathingRoom() {
        val strip = layoutStrip(368)
        val dp = strip::dp

        for (button in listOf(strip.pause, strip.stop, strip.minimize)) {
            assertEquals("button must be 36 dp tall", dp(36), button.height)
            assertTrue("button touches the top edge: ${button.top}", button.top >= dp(6))
            assertTrue(
                "button touches the bottom edge: ${button.bottom}",
                button.bottom <= dp(52) - dp(6)
            )
        }
        assertTrue("pause/stop overlap", strip.stop.left >= strip.pause.right)
        assertTrue("stop/minimize overlap", strip.minimize.left >= strip.stop.right)
        assertTrue("controls escape the strip", strip.minimize.right <= dp(368) - dp(6))
        assertTrue(
            "timer overlaps pause",
            strip.pause.absLeft() >= strip.timer.absLeft() + strip.timer.width
        )
    }

    @Test
    fun nameGetsTheWholeTextColumn() {
        val strip = layoutStrip(368)
        val dp = strip::dp

        assertTrue(
            "name column is still squeezed: ${strip.name.width} px",
            strip.name.width >= dp(176)
        )
        assertTrue(
            "name overlaps pause",
            strip.pause.absLeft() >= strip.name.absLeft() + strip.name.width
        )
    }

    @Test
    fun timerAndRunSitUnderTheName() {
        val strip = layoutStrip(368)
        val dp = strip::dp

        assertTrue(
            "name is not above the timer",
            strip.name.absTop() + strip.name.height <= strip.timer.absTop()
        )
        assertTrue(
            "name is not above the run label",
            strip.name.absTop() + strip.name.height <= strip.run.absTop()
        )
        assertTrue("run label overlaps timer", strip.run.left >= strip.timer.right)
        assertTrue(
            "run label escapes its row",
            strip.run.absLeft() + strip.run.width <= strip.name.absLeft() + strip.name.width
        )
        assertTrue("timer is too close to the top", strip.timer.absTop() >= dp(6))
    }

    @Test
    fun portraitNameColumnAlsoFits() {
        val strip = layoutStrip(328)
        val dp = strip::dp

        assertTrue(
            "portrait name column is too narrow: ${strip.name.width} px",
            strip.name.width >= dp(140)
        )
        assertTrue(
            "portrait timer overlaps pause",
            strip.pause.absLeft() >= strip.timer.absLeft() + strip.timer.width
        )
    }
}
