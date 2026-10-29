package com.clahbot.ui.design

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

/** Single source of truth shared by the floating overlay and the dashboard. */
object Ds {

    const val BACKDROP = 0xFF0A101D.toInt()
    const val PANEL = 0xFF111A2E.toInt()
    const val PANEL_BORDER = 0xFF22304D.toInt()
    const val TEXT = 0xFFE6EBF5.toInt()
    const val MUTED = 0xFF9FB0CF.toInt()
    const val ACCENT = 0xFF2563EB.toInt()
    const val LINK = 0xFF60A5FA.toInt()
    const val RECORD = 0xFFDC2626.toInt()
    const val PLAY = 0xFF16A34A.toInt()
    const val SWITCH_ON = 0xFF16A34A.toInt()
    const val SWITCH_OFF_TRACK = 0xFF1B2742.toInt()
    const val SWITCH_OFF_BORDER = 0xFF3A4A70.toInt()
    const val SELECTED_ROW = 0xFF172447.toInt()
    const val SELECTED_ROW_BORDER = 0xFF2F4A86.toInt()
    const val INPUT = 0xFF0D1526.toInt()
    const val BUTTON = 0xFF1B2742.toInt()
    const val DIVIDER = 0xFF1C2944.toInt()
    const val SUCCESS = 0xFF4ADE80.toInt()
    const val SUCCESS_BG = 0xFF0F2A1C.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val WARN_BG = 0xFF3B2A0B.toInt()
    const val DANGER_OUTLINE = 0xFFF87171.toInt()
    const val DANGER_TEXT = 0xFFFCA5A5.toInt()
    const val GOLD = 0xFFF5B82E.toInt()
    const val ELIXIR = 0xFFD946EF.toInt()
    const val DARK_ELIXIR = 0xFF5B5BD6.toInt()

    const val RADIUS_PANEL = 16
    const val RADIUS_CARD = 14
    const val RADIUS_ROW = 12
    const val RADIUS_BUTTON = 10

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun text(
        context: Context,
        sizeSp: Float,
        color: Int = TEXT,
        bold: Boolean = false,
        caps: Boolean = false
    ): TextView = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (caps) {
            letterSpacing = 0.08f
            isAllCaps = true
        }
        includeFontPadding = false
    }

    fun rounded(
        color: Int,
        radiusDp: Int,
        context: Context,
        strokeColor: Int? = null,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(color)
        if (strokeColor != null) setStroke(dp(context, strokeDp), strokeColor)
    }

    fun pill(
        color: Int,
        context: Context,
        strokeColor: Int? = null
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, 999).toFloat()
        setColor(color)
        if (strokeColor != null) setStroke(dp(context, 1), strokeColor)
    }

    fun divider(context: Context): android.view.View = android.view.View(context).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 1)
        )
    }

    fun spacer(context: Context, heightDp: Int): android.view.View =
        android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, heightDp)
            )
        }

    fun marginParams(
        width: Int,
        height: Int,
        start: Int = 0,
        top: Int = 0,
        end: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height).apply {
        marginStart = start
        this.topMargin = top
        marginEnd = end
        this.bottomMargin = bottom
    }

    fun compactNumber(value: Long): String = when {
        value >= 1_000_000_000L -> trim(value / 1_000_000_000.0) + "B"
        value >= 1_000_000L -> trim(value / 1_000_000.0) + "M"
        value >= 1_000L -> trim(value / 1_000.0) + "K"
        else -> value.toString()
    }

    private fun trim(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", rounded)
    }
}

/** A compact 1 px separator that can be dropped between settings rows. */
fun divider(context: Context): android.view.View = Ds.divider(context)
