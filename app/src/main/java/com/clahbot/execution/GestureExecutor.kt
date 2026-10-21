package com.clahbot.execution

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.clahbot.data.models.TargetPoint
import com.clahbot.service.ClahAccessibilityService
import kotlin.math.pow
import kotlin.random.Random

class GestureExecutor(
    private val jitterPx: Int = 10
) {

    fun tap(point: TargetPoint, callback: ((Boolean) -> Unit)? = null) {
        val service = ClahAccessibilityService.instance ?: run {
            com.clahbot.data.SharedVisionBuffer.setStatus("ERROR: Accessibility service not enabled")
            callback?.invoke(false)
            return
        }

        val x = (point.x + Random.nextInt(-jitterPx, jitterPx + 1)).toFloat()
        val y = (point.y + Random.nextInt(-jitterPx, jitterPx + 1)).toFloat()
        val duration = gaussianDelay(80, 30).toLong()

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    fun swipeBezier(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        durationMs: Long = 400,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val service = ClahAccessibilityService.instance ?: run {
            com.clahbot.data.SharedVisionBuffer.setStatus("ERROR: Accessibility service not enabled")
            callback?.invoke(false)
            return
        }

        val path = buildBezierPath(fromX, fromY, toX, toY)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    private fun buildBezierPath(x0: Float, y0: Float, x3: Float, y3: Float): Path {
        val path = Path()
        path.moveTo(x0, y0)

        val cp1x = x0 + (x3 - x0) * 0.25f + Random.nextInt(-30, 30)
        val cp1y = y0 + (y3 - y0) * 0.1f + Random.nextInt(-30, 30)
        val cp2x = x0 + (x3 - x0) * 0.75f + Random.nextInt(-30, 30)
        val cp2y = y0 + (y3 - y0) * 0.9f + Random.nextInt(-30, 30)

        val steps = 20
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val x = cubicBezier(x0, cp1x, cp2x, x3, t)
            val y = cubicBezier(y0, cp1y, cp2y, y3, t)
            path.lineTo(x, y)
        }
        return path
    }

    private fun cubicBezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val u = 1 - t
        return u.pow(3) * p0 + 3 * u.pow(2) * t * p1 + 3 * u * t.pow(2) * p2 + t.pow(3) * p3
    }

    private fun gaussianDelay(mean: Int, stdDev: Int): Int {
        val u1 = Random.nextDouble()
        val u2 = Random.nextDouble()
        val z = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        return (mean + z * stdDev).toInt().coerceAtLeast(50)
    }

    suspend fun delayGaussian(meanMs: Int = 500, stdDevMs: Int = 150) {
        kotlinx.coroutines.delay(gaussianDelay(meanMs, stdDevMs).toLong())
    }
}
