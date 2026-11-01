package com.clahbot.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.clahbot.ui.design.Ds

/**
 * One resource total: icon, a shrinking label/subtitle column, and an exact
 * total that is always measured at its full single-line width. When the label
 * column would fall below [minLeftWidthDp], the total moves to its own line
 * instead of squeezing or clipping.
 */
class LootRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    val left = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val total = TextView(context).apply {
        setTextColor(Ds.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, MAX_TEXT_SP)
        setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
        isSingleLine = true
        maxLines = 1
        // The view is sized to the glyphs and placed at the right edge, so
        // START keeps the first digit inside even if a Layout reports extra
        // width; END used to push the leftmost digit out of the view.
        gravity = Gravity.START
        fontFeatureSettings = "tnum"
    }

    var twoLine: Boolean = false
        private set

    private val iconSizePx = Ds.dp(context, ICON_DP)
    private val gapPx = Ds.dp(context, GAP_DP)
    private val minLeftPx = Ds.dp(context, MIN_LEFT_DP)
    private val ownLineGapPx = Ds.dp(context, OWN_LINE_GAP_DP)

    init {
        addView(icon)
        addView(left)
        addView(total)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        icon.measure(
            MeasureSpec.makeMeasureSpec(iconSizePx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(iconSizePx, MeasureSpec.EXACTLY)
        )

        val naturalTotal = measureTotal(Integer.MAX_VALUE)
        val padding = paddingLeft + paddingRight
        val contentWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            naturalTotal + iconSizePx + gapPx * 2 + minLeftPx
        } else {
            (widthSize - padding).coerceAtLeast(0)
        }

        val leftOneLine = contentWidth - iconSizePx - gapPx - naturalTotal - gapPx
        twoLine = leftOneLine < minLeftPx
        val wantedHeight: Int
        if (twoLine) {
            val leftWidth = (contentWidth - iconSizePx - gapPx).coerceAtLeast(0)
            left.measure(
                MeasureSpec.makeMeasureSpec(leftWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            measureTotal(contentWidth)
            val topHeight = maxOf(iconSizePx, left.measuredHeight)
            wantedHeight = paddingTop + paddingBottom + topHeight + ownLineGapPx +
                total.measuredHeight
        } else {
            left.measure(
                MeasureSpec.makeMeasureSpec(leftOneLine.coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            wantedHeight = paddingTop + paddingBottom +
                maxOf(iconSizePx, left.measuredHeight, total.measuredHeight)
        }
        val measuredWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            iconSizePx + gapPx + left.measuredWidth + gapPx + total.measuredWidth + padding
        } else {
            widthSize
        }
        setMeasuredDimension(
            measuredWidth,
            resolveSize(wantedHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val contentTop = paddingTop
        val contentHeight = (b - t) - paddingTop - paddingBottom
        val iconTop = contentTop + ((maxOf(iconSizePx, left.measuredHeight) - iconSizePx) / 2)
        icon.layout(paddingLeft, iconTop, paddingLeft + iconSizePx, iconTop + iconSizePx)
        val leftLeft = paddingLeft + iconSizePx + gapPx
        val leftRight = leftLeft + left.measuredWidth
        if (!twoLine) {
            val leftTop = contentTop + ((contentHeight - left.measuredHeight) / 2)
            left.layout(leftLeft, leftTop, leftRight, leftTop + left.measuredHeight)
            val totalLeft = (width - paddingRight - total.measuredWidth)
                .coerceAtLeast(paddingLeft)
            val totalTop = contentTop + ((contentHeight - total.measuredHeight) / 2)
            total.layout(totalLeft, totalTop, width - paddingRight, totalTop + total.measuredHeight)
        } else {
            left.layout(leftLeft, contentTop, leftRight, contentTop + left.measuredHeight)
            val topHeight = maxOf(iconSizePx, left.measuredHeight)
            val totalTop = contentTop + topHeight + ownLineGapPx
            val totalLeft = (width - paddingRight - total.measuredWidth)
                .coerceAtLeast(paddingLeft)
            total.layout(totalLeft, totalTop, width - paddingRight, totalTop + total.measuredHeight)
        }
    }

    /**
     * Width of the exact total at its natural single-line width. Measured from
     * the paint instead of an UNSPECIFIED View measure: that path leaves
     * TextView's internal Layout a million pixels wide, and END gravity then
     * draws the text outside the view. Shrinks only as a last resort.
     */
    private fun measureTotal(maxWidth: Int): Int {
        var size = MAX_TEXT_SP
        val text = total.text.toString()
        while (true) {
            total.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            val natural = kotlin.math.ceil(total.paint.measureText(text).toDouble())
                .toInt()
                .coerceAtLeast(1)
            if (natural <= maxWidth || size <= MIN_TEXT_SP) {
                total.measure(
                    MeasureSpec.makeMeasureSpec(natural, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                return natural
            }
            size -= 0.5f
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        LayoutParams(context, attrs)

    companion object {
        private const val ICON_DP = 20
        private const val GAP_DP = 8
        private const val MIN_LEFT_DP = 84
        private const val OWN_LINE_GAP_DP = 4
        private const val MAX_TEXT_SP = 15f
        private const val MIN_TEXT_SP = 8f
    }
}
