package com.clahbot.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Wall Ring button is blue, and this sampler is the only path from a
 * price line to a resource. Blue must never classify: that is what makes
 * "never buy wall rings" structural instead of a rule promise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapIconColorSamplerTest {

    private fun sampleWith(color: Int?): ResourceKind? {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(30, 30, 30))
        if (color != null) {
            canvas.drawRect(70f, 38f, 105f, 62f, Paint().apply { this.color = color })
        }
        return BitmapIconColorSampler(bitmap).classify(OcrBox(10, 40, 60, 60))
    }

    @Test
    fun `gold coin is gold`() {
        assertEquals(ResourceKind.GOLD, sampleWith(Color.rgb(255, 190, 40)))
    }

    @Test
    fun `elixir blob is elixir`() {
        assertEquals(ResourceKind.ELIXIR, sampleWith(Color.rgb(200, 40, 230)))
    }

    @Test
    fun `wall ring blue is never classified`() {
        assertNull(sampleWith(Color.rgb(40, 120, 240)))
        assertNull(sampleWith(Color.rgb(30, 90, 200)))
        assertNull(sampleWith(Color.rgb(70, 150, 255)))
    }

    @Test
    fun `gem green is never gold or elixir`() {
        assertNull(sampleWith(Color.rgb(40, 220, 60)))
    }

    @Test
    fun `empty space with no icon is null`() {
        assertNull(sampleWith(null))
    }
}
