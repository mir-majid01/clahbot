package com.clahbot.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.clahbot.R
import com.clahbot.ui.design.Ds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Screenshot matrix for the floating Stats loot row. Every combination is
 * measured, asserted to keep the full total (no left-digit clipping), drawn to
 * a PNG and uploaded by CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LootRowLayoutTest {

    private val values = listOf(
        0L,
        1L,
        999L,
        1_000L,
        999_999L,
        1_000_000L,
        5_711_279L,
        999_999_999_999L
    )
    private val widthScales = listOf(0.8f, 1.0f, 1.3f)
    private val fontScales = listOf(1.0f, 1.3f)
    private val orientations = listOf(
        Configuration.ORIENTATION_PORTRAIT,
        Configuration.ORIENTATION_LANDSCAPE
    )

    @Test
    fun totalIsNeverClipped() {
        val base = RuntimeEnvironment.getApplication()
        val output = File("build/reports/loot-matrix").apply { mkdirs() }
        var cases = 0
        for (widthScale in widthScales) {
            for (fontScale in fontScales) {
                for (orientation in orientations) {
                    val context = configuredContext(base, fontScale, orientation)
                    val widthPx = (300f * widthScale * context.resources.displayMetrics.density)
                        .roundToInt()
                    for (value in values) {
                        val row = buildRow(context, value)
                        row.measure(
                            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        row.layout(0, 0, row.measuredWidth, row.measuredHeight)

                        val name = "w${widthScale}_f${fontScale}_" +
                            (if (orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port") +
                            "_$value"

                        val bitmap = Bitmap.createBitmap(
                            row.measuredWidth,
                            row.measuredHeight.coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        row.draw(Canvas(bitmap))
                        verify(row, value, name, bitmap)
                        File(output, "$name.png").outputStream().use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                        bitmap.recycle()
                        cases++
                    }
                }
            }
        }
        assertEquals(widthScales.size * fontScales.size * orientations.size * values.size, cases)
        assertTrue("screenshots missing", output.listFiles()?.isNotEmpty() == true)
    }

    private fun verify(row: LootRowLayout, value: Long, name: String, bitmap: Bitmap) {
        val total = row.total
        val layout = requireNotNull(total.layout) { "$name: total has no layout" }
        val lineWidth = layout.getLineWidth(0)
        val paintWidth = total.paint.measureText(total.text.toString())
        assertTrue(
            "$name: view narrower than its text (view=${total.width} paint=$paintWidth)",
            total.width + 0.75f >= paintWidth
        )
        assertTrue(
            "$name: view narrower than its line (view=${total.width} line=$lineWidth)",
            total.width + 0.75f >= lineWidth
        )
        assertTrue(
            "$name: total starts outside the row (left=${total.left})",
            total.left >= row.paddingLeft
        )
        assertTrue(
            "$name: total ends outside the row (right=${total.right} row=${row.width})",
            total.right <= row.width - row.paddingRight
        )
        assertTrue("$name: total has no width", total.width > 0)
        assertTrue("$name: empty total", total.text.toString().isNotBlank())
        if (value >= 1_000_000L) {
            assertTrue(
                "$name: expected a grouped total, got ${total.text}",
                total.text.contains(',')
            )
        }
        if (row.twoLine) {
            assertTrue(
                "$name: total should sit below the label column",
                total.top >= row.left.bottom
            )
        }
        assertTrue("$name: total was not drawn", hasInk(bitmap, total))
    }

    private fun hasInk(bitmap: Bitmap, child: View): Boolean {
        val left = child.left.coerceAtLeast(0)
        val top = child.top.coerceAtLeast(0)
        val right = child.right.coerceAtMost(bitmap.width)
        val bottom = child.bottom.coerceAtMost(bitmap.height)
        for (y in top until bottom) {
            for (x in left until right) {
                if (bitmap.getPixel(x, y) != Ds.PANEL) return true
            }
        }
        return false
    }

    private fun configuredContext(
        base: Context,
        fontScale: Float,
        orientation: Int
    ): Context {
        val configuration = Configuration(base.resources.configuration).apply {
            this.fontScale = fontScale
            this.orientation = orientation
        }
        return base.createConfigurationContext(configuration)
    }

    private fun buildRow(context: Context, value: Long): LootRowLayout =
        LootRowLayout(context).apply {
            setBackgroundColor(Ds.PANEL)
            icon.setImageResource(R.drawable.ic_gold)
            left.addView(Ds.text(context, 12.5f, Ds.TEXT, bold = true).apply {
                text = "Gold"
                isSingleLine = true
                maxLines = 1
            })
            left.addView(
                Ds.text(context, 10f, Ds.MUTED).apply {
                    text = "loot 3,683,329 + bonus 1,703,950"
                    isSingleLine = true
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            total.text = String.format(Locale.US, "%,d", value)
        }
}
