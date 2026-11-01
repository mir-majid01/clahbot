package com.clahbot.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import com.clahbot.data.EffectStyle
import com.clahbot.data.TouchEffectConfig
import com.clahbot.data.models.GesturePoint
import kotlin.math.max

/**
 * One non-touchable, non-focusable overlay draws touch feedback for recording
 * (real touch points) and playback (injected gesture paths). The view allocates
 * only when a touch/path arrives, keeps at most [MAX_EFFECTS] live effects, and
 * runs a Choreographer frame callback only while something is visible.
 */
class TouchEffectsView(context: Context) : View(context) {

    private data class PointEffect(
        val x: Float,
        val y: Float,
        val startMs: Long,
        val style: EffectStyle,
        val color: Int,
        val sizePx: Float,
        val fadeMs: Long
    )

    private class Trail(
        val pointerId: Int,
        val points: MutableList<GesturePoint> = mutableListOf(),
        var lastMs: Long = 0L,
        var active: Boolean = true
    )

    private class PlaybackPath(
        val points: List<GesturePoint>,
        val startAtMs: Long,
        val durationMs: Long,
        val style: EffectStyle,
        val color: Int,
        val sizePx: Float,
        val fadeMs: Long,
        val trail: Boolean
    )

    private val points = mutableListOf<PointEffect>()
    private val trails = mutableMapOf<Int, Trail>()
    private val paths = mutableListOf<PlaybackPath>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPath = Path()
    private var frameScheduled = false

    @Volatile
    var suppressed: Boolean = false
        set(value) {
            field = value
            if (value) {
                points.clear()
                trails.clear()
                paths.clear()
                invalidate()
            }
        }

    private var recordingConfig: TouchEffectConfig? = null
    private var playingConfig: TouchEffectConfig? = null

    fun setRecordingConfig(config: TouchEffectConfig) {
        recordingConfig = config
    }

    fun setPlayingConfig(config: TouchEffectConfig) {
        playingConfig = config
    }

    @Synchronized
    fun recordingTouch(pointerId: Int, x: Float, y: Float, active: Boolean) {
        val config = recordingConfig ?: return
        if (!config.enabled || suppressed) return
        val now = SystemClock.uptimeMillis()
        if (!active) {
            trails.remove(pointerId)
            addPoint(x, y, now, config)
            requestFrame()
            return
        }
        val trail = trails.getOrPut(pointerId) { Trail(pointerId) }
        trail.lastMs = now
        trail.active = true
        trail.points += GesturePoint(x, y, 0L)
        if (trail.points.size > 48) trail.points.removeAt(0)
        if (config.trail) {
            requestFrame()
        } else if (trail.points.size == 1) {
            addPoint(x, y, now, config)
        }
    }

    @Synchronized
    fun playPath(
        pathPoints: List<GesturePoint>,
        startDelayMs: Long,
        durationMs: Long
    ) {
        val config = playingConfig ?: return
        if (!config.enabled || suppressed || pathPoints.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        paths += PlaybackPath(
            points = pathPoints,
            startAtMs = now + startDelayMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(1L),
            style = config.style,
            color = config.color,
            sizePx = dp(config.sizeDp),
            fadeMs = config.fadeMs.toLong(),
            trail = config.trail
        )
        while (paths.size > MAX_EFFECTS) paths.removeAt(0)
        requestFrame()
    }

    private fun addPoint(x: Float, y: Float, now: Long, config: TouchEffectConfig) {
        points += PointEffect(
            x = x,
            y = y,
            startMs = now,
            style = config.style,
            color = config.color,
            sizePx = dp(config.sizeDp),
            fadeMs = config.fadeMs.toLong()
        )
        while (points.size > MAX_EFFECTS) points.removeAt(0)
    }

    private fun requestFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        if (suppressed) {
            points.clear()
            trails.clear()
            paths.clear()
            invalidate()
            return@FrameCallback
        }
        val now = SystemClock.uptimeMillis()
        points.removeAll { now - it.startMs > it.fadeMs }
        paths.removeAll { now > it.startAtMs + it.durationMs + it.fadeMs }
        val stale = trails.filterValues { now - it.lastMs > MAX_TRAIL_GAP_MS }.keys.toList()
        stale.forEach { trails.remove(it) }
        invalidate()
        if (points.isNotEmpty() || paths.isNotEmpty() || trails.isNotEmpty()) {
            requestFrame()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        for (trail in trails.values) {
            if (trail.points.size < 2) continue
            trailPath.reset()
            val first = trail.points.first()
            trailPath.moveTo(first.x, first.y)
            for (index in 1 until trail.points.size) {
                trailPath.lineTo(trail.points[index].x, trail.points[index].y)
            }
            trailPaint.style = Paint.Style.STROKE
            trailPaint.strokeCap = Paint.Cap.ROUND
            trailPaint.strokeJoin = Paint.Join.ROUND
            trailPaint.strokeWidth = dp(3)
            trailPaint.color = withAlpha(recordingConfig?.color ?: Color.WHITE, 150)
            canvas.drawPath(trailPath, trailPaint)
        }
        for (effect in points) {
            drawEffect(canvas, effect.x, effect.y, effect.startMs, effect.style, effect.color, effect.sizePx, effect.fadeMs, now)
        }
        for (path in paths) {
            val elapsed = now - path.startAtMs
            if (elapsed < 0L) continue
            val progress = (elapsed.toFloat() / path.durationMs).coerceIn(0f, 1f)
            pointAt(path.points, progress)
            if (path.trail) {
                drawPlayedTrail(canvas, path, progress)
            }
            drawEffect(
                canvas, outX, outY,
                if (elapsed <= path.durationMs) now else path.startAtMs + path.durationMs,
                path.style, path.color, path.sizePx, path.fadeMs, now
            )
        }
    }

    private var outX: Float = 0f
    private var outY: Float = 0f

    private fun drawEffect(
        canvas: Canvas,
        x: Float,
        y: Float,
        startMs: Long,
        style: EffectStyle,
        color: Int,
        sizePx: Float,
        fadeMs: Long,
        now: Long
    ) {
        val life = ((now - startMs).toFloat() / fadeMs).coerceIn(0f, 1f)
        val alpha = ((1f - life) * 255f).toInt().coerceIn(0, 255)
        if (alpha <= 0) return
        val radius = sizePx / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2)
        paint.color = withAlpha(color, alpha)
        when (style) {
            EffectStyle.RING -> {
                canvas.drawCircle(x, y, radius * (0.4f + 0.6f * life), paint)
            }

            EffectStyle.DOT -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, radius * 0.45f, paint)
            }

            EffectStyle.RIPPLE -> {
                canvas.drawCircle(x, y, radius * (0.2f + 0.8f * life), paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, (radius * 0.16f).coerceAtLeast(dp(2)), paint)
            }
        }
    }

    private fun drawPlayedTrail(canvas: Canvas, path: PlaybackPath, progress: Float) {
        if (path.points.size < 2) return
        trailPath.reset()
        val count = max(2, (path.points.size * progress).toInt())
        val first = path.points.first()
        trailPath.moveTo(first.x, first.y)
        for (index in 1 until count.coerceAtMost(path.points.size)) {
            trailPath.lineTo(path.points[index].x, path.points[index].y)
        }
        trailPaint.style = Paint.Style.STROKE
        trailPaint.strokeCap = Paint.Cap.ROUND
        trailPaint.strokeWidth = dp(3)
        trailPaint.color = withAlpha(path.color, 130)
        canvas.drawPath(trailPath, trailPaint)
    }

    private fun pointAt(points: List<GesturePoint>, progress: Float) {
        if (points.size == 1) {
            outX = points[0].x
            outY = points[0].y
            return
        }
        val index = ((points.size - 1) * progress).toInt().coerceIn(0, points.size - 2)
        val start = points[index]
        val end = points[index + 1]
        val fraction = progress * (points.size - 1) - index
        outX = start.x + (end.x - start.x) * fraction
        outY = start.y + (end.y - start.y) * fraction
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun dp(value: Int): Float = value * resources.displayMetrics.density

    companion object {
        private const val MAX_EFFECTS = 10
        private const val MAX_TRAIL_GAP_MS = 250L
    }
}
