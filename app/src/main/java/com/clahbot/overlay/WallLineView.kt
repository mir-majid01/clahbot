package com.clahbot.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Draws the A→B wall line and the tap points that will be used, so the user
 * can see exactly where the upgrader will touch. Not touchable; the markers
 * are separate views.
 */
class WallLineView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var ax = 0.5f
    private var ay = 0.75f
    private var bx = 0.9f
    private var by = 0.5f
    private var points = DEFAULT_POINTS

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun setLine(ax: Float, ay: Float, bx: Float, by: Float, count: Int = DEFAULT_POINTS) {
        this.ax = ax.coerceIn(0f, 1f)
        this.ay = ay.coerceIn(0f, 1f)
        this.bx = bx.coerceIn(0f, 1f)
        this.by = by.coerceIn(0f, 1f)
        points = count.coerceIn(2, 120)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val startX = ax * width
        val startY = ay * height
        val endX = bx * width
        val endY = by * height
        linePaint.shader = LinearGradient(
            startX, startY, endX, endY,
            Color.rgb(34, 197, 94), Color.rgb(239, 68, 68),
            Shader.TileMode.CLAMP
        )
        canvas.drawLine(startX, startY, endX, endY, linePaint)

        val radius = 3.5f * resources.displayMetrics.density
        val halo = radius * 1.9f
        for (index in 0 until points) {
            val t = if (points <= 1) 0f else index / (points - 1f)
            val x = startX + (endX - startX) * t
            val y = startY + (endY - startY) * t
            canvas.drawCircle(x, y, halo, haloPaint)
            canvas.drawCircle(x, y, radius, dotPaint)
        }
    }

    companion object {
        private const val DEFAULT_POINTS = 40
    }
}
