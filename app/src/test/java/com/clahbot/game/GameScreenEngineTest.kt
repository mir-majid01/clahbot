package com.clahbot.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * State-engine fixtures in the same shape the real screens produce: an
 * OCR page whose boxes share the captured-frame pixel space plus a frame with
 * the colour anchors drawn in. Synthetic, so the accuracy numbers reported in
 * BATCH3_REPORT.md come from the device pass, not from these fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameScreenEngineTest {

    private val frameW = 1000
    private val frameH = 500
    private val engine = GameScreenEngine()

    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        OcrLine(
            text = text,
            box = OcrBox(
                left = (left * frameW).toInt(),
                top = (top * frameH).toInt(),
                right = (right * frameW).toInt(),
                bottom = (bottom * frameH).toInt()
            ),
            elements = emptyList()
        )

    private fun page(vararg lines: OcrLine) = OcrPage(frameW, frameH, lines.toList())

    private fun frame(
        background: Int = Color.rgb(110, 110, 110),
        draw: (Canvas) -> Unit = {}
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        draw(canvas)
        return bitmap
    }

    private fun drawRedX(canvas: Canvas) {
        val paint = Paint().apply { color = Color.rgb(220, 30, 30) }
        canvas.drawRect(860f, 90f, 900f, 130f, paint)
    }

    private fun drawGem(canvas: Canvas) {
        val paint = Paint().apply { color = Color.rgb(40, 220, 60) }
        canvas.drawRect(760f, 300f, 780f, 322f, paint)
    }

    private fun drawConfirm(canvas: Canvas) {
        val paint = Paint().apply { color = Color.rgb(40, 200, 60) }
        canvas.drawRect(400f, 380f, 600f, 440f, paint)
    }

    @Test
    fun `home needs battle bottom-left and build bottom-right`() {
        val observation = engine.inspect(
            frame(),
            page(
                line("Battle", 0.04f, 0.86f, 0.14f, 0.93f),
                line("Build", 0.84f, 0.86f, 0.93f, 0.93f)
            ),
            "com.supercell.clashofclans"
        )
        assertEquals(GameState.HOME, observation.state)
    }

    @Test
    fun `attack menu needs find a match`() {
        val observation = engine.inspect(
            frame(),
            page(
                line("Find a Match 1600", 0.10f, 0.50f, 0.35f, 0.56f),
                line("Multiplayer", 0.05f, 0.30f, 0.16f, 0.36f),
                line("Shop", 0.84f, 0.40f, 0.92f, 0.46f)
            ),
            "com.supercell.clashofclans"
        )
        assertEquals(GameState.ATTACK_MENU, observation.state)
    }

    @Test
    fun `battle anchors outrank everything except dialogs`() {
        val observation = engine.inspect(
            frame(),
            page(
                line("Battle ends in 2:59", 0.42f, 0.04f, 0.58f, 0.09f),
                line("Available Loot", 0.05f, 0.20f, 0.20f, 0.26f),
                line("Surrender", 0.02f, 0.86f, 0.10f, 0.92f)
            ),
            "com.supercell.clashofclans"
        )
        assertEquals(GameState.BATTLE, observation.state)
    }

    @Test
    fun `results anchors classify the result screen`() {
        val observation = engine.inspect(
            frame(),
            page(
                line("VICTORY", 0.42f, 0.08f, 0.58f, 0.15f),
                line("You got", 0.06f, 0.30f, 0.16f, 0.36f),
                line("Total Destruction 100%", 0.06f, 0.80f, 0.30f, 0.86f)
            ),
            "com.supercell.clashofclans"
        )
        assertEquals(GameState.RESULTS, observation.state)
    }

    @Test
    fun `searching needs the caption or a white screen`() {
        val withText = engine.inspect(
            frame(background = Color.WHITE),
            page(line("Searching for opponent", 0.35f, 0.46f, 0.65f, 0.54f)),
            "com.supercell.clashofclans"
        )
        assertEquals(GameState.SEARCHING, withText.state)
    }

    @Test
    fun `a black frame is loading`() {
        val observation = engine.inspect(frame(background = Color.BLACK), null, null)
        assertEquals(GameState.LOADING, observation.state)
    }

    @Test
    fun `another app is not clash no matter the frame`() {
        val observation = engine.inspect(
            frame(),
            page(line("Battle", 0.04f, 0.86f, 0.14f, 0.93f)),
            "com.android.settings"
        )
        assertEquals(GameState.NOT_CLASH, observation.state)
        assertEquals(1f, observation.confidence)
    }

    @Test
    fun `connection lost is a popup with a red close X`() {
        val observation = engine.inspect(
            frame(draw = { drawRedX(it) }),
            page(
                line("Connection lost", 0.35f, 0.40f, 0.65f, 0.48f),
                line("Reload", 0.45f, 0.62f, 0.55f, 0.68f)
            ),
            "com.supercell.clashofclans"
        )
        assertEquals(GameState.POPUP, observation.state)
        assertEquals(PopupKind.CONNECTION_LOST, observation.popupKind)
        assertNotNull(observation.closeButton)
        assertTrue(observation.safeTargets.isNotEmpty())
    }

    @Test
    fun `gem dialog forbids the buy caption`() {
        val buy = line("Buy", 0.72f, 0.60f, 0.78f, 0.66f)
        val observation = engine.inspect(
            frame(draw = {
                drawRedX(it)
                drawGem(it)
            }),
            page(line("You need more gems", 0.35f, 0.30f, 0.65f, 0.38f), buy),
            "com.supercell.clashofclans"
        )
        assertEquals(GameState.POPUP, observation.state)
        assertEquals(PopupKind.GEM_SPEND, observation.popupKind)
        assertTrue("gem icon must become a forbidden box", observation.forbidden.isNotEmpty())
        val buyBox = buy.box
        assertNotNull(buyBox)
        val buyScreenBox = ScreenBox(
            left = buyBox!!.left,
            top = buyBox.top,
            right = buyBox.right,
            bottom = buyBox.bottom
        )
        assertTrue(
            "the Buy caption must sit on a forbidden box",
            observation.forbidden.any { it.intersects(buyScreenBox) }
        )
        assertTrue(
            "safe targets must never intersect forbidden boxes",
            observation.safeTargets.none { target ->
                observation.forbidden.any { it.intersects(target) }
            }
        )
    }

    @Test
    fun `colour only popup needs a scrim or buttons, not just grass`() {
        val plain = engine.inspect(
            frame(background = Color.rgb(60, 140, 60)),
            null,
            "com.supercell.clashofclans"
        )
        assertEquals(GameState.UNKNOWN, plain.state)
        val popup = engine.inspect(
            frame(draw = {
                drawRedX(it)
                drawConfirm(it)
            }),
            null,
            "com.supercell.clashofclans"
        )
        assertEquals(GameState.POPUP, popup.state)
        assertEquals(PopupKind.GENERIC, popup.popupKind)
        assertNotNull(popup.closeButton)
        assertNotNull(popup.confirmButton)
    }

    @Test
    fun `empty ocr and mid grey is unknown`() {
        val observation = engine.inspect(frame(), null, "com.supercell.clashofclans")
        assertEquals(GameState.UNKNOWN, observation.state)
        assertNull(observation.popupKind)
    }
}
