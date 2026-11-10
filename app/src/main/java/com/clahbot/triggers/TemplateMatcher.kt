package com.clahbot.triggers

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds a reference image inside a live frame without an external vision
 * library. Both images become grayscale and are reduced to a small working
 * width, then compared with zero-mean normalized cross-correlation (NCC) over a
 * few zoom levels. NCC is invariant to brightness and contrast, so the match
 * survives a changed background and small lighting shifts; the zoom levels
 * absorb resolution/scale differences. A match is only returned when it is
 * above the confidence threshold and clearly better than any other location, so
 * an ambiguous screen pauses instead of acting.
 *
 * Pure Kotlin over IntArray pixels: no Android types, so it is fully unit-tested
 * and can run off the main thread.
 */
object TemplateMatcher {

    data class Frame(val pixels: IntArray, val width: Int, val height: Int)

    data class Config(
        val threshold: Float = 0.85f,
        val ambiguityMargin: Float = 0.05f,
        val workWidth: Int = 128,
        val scales: List<Float> = listOf(0.85f, 1.0f, 1.15f),
        val stride: Int = 2
    )

    /** A confident match rectangle in the ORIGINAL frame's pixel space. */
    data class Match(
        val score: Float,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val scale: Float
    )

    fun match(frame: Frame, template: Frame, config: Config = Config()): Match? {
        if (frame.width <= 0 || frame.height <= 0) return null
        if (template.width <= 0 || template.height <= 0) return null
        if (frame.pixels.size < frame.width * frame.height) return null
        if (template.pixels.size < template.width * template.height) return null
        if (config.scales.isEmpty()) return null

        val frameGray = grayscale(frame)
        val tplGray = grayscale(template)
        val factor = minOf(1f, config.workWidth.toFloat() / frame.width)
        val workW = (frame.width * factor).roundToInt().coerceAtLeast(1)
        val workH = (frame.height * factor).roundToInt().coerceAtLeast(1)
        val frameSmall = downscale(frameGray, frame.width, frame.height, workW, workH)
        val stride = config.stride.coerceAtLeast(1)

        val layers = ArrayList<Layer>()
        for (scale in config.scales) {
            if (scale <= 0f) continue
            val tw = (template.width * factor * scale).roundToInt()
            val th = (template.height * factor * scale).roundToInt()
            if (tw < 6 || th < 6 || tw > workW || th > workH) continue
            val tpl = downscale(tplGray, template.width, template.height, tw, th)
            val stats = stats(tpl)
            if (stats.sumSqDev <= 1e-3f) continue
            layers += Layer(tpl, tw, th, stats, scale)
        }
        if (layers.isEmpty()) return null

        // Pass 1: the single best window across every scale and location.
        var best: Candidate? = null
        for (layer in layers) {
            val maxX = workW - layer.w
            val maxY = workH - layer.h
            var y = 0
            while (y <= maxY) {
                var x = 0
                while (x <= maxX) {
                    val score = ncc(frameSmall, workW, x, y, layer)
                    val current = best
                    if (current == null || score > current.score) {
                        best = Candidate(score, x, y, layer.w, layer.h, layer.scale)
                    }
                    x += stride
                }
                y += stride
            }
        }
        val top = best ?: return null
        if (top.score < config.threshold) return null

        // Pass 2: the strongest score at a clearly different location. Equal
        // candidates mean the screen is ambiguous, so nothing may be pressed.
        val sep = minOf(top.w, top.h).coerceAtLeast(1)
        val bx = top.x + top.w / 2
        val by = top.y + top.h / 2
        var second = Float.NEGATIVE_INFINITY
        for (layer in layers) {
            val maxX = workW - layer.w
            val maxY = workH - layer.h
            var y = 0
            while (y <= maxY) {
                var x = 0
                while (x <= maxX) {
                    val cx = x + layer.w / 2
                    val cy = y + layer.h / 2
                    if (abs(cx - bx) > sep || abs(cy - by) > sep) {
                        val score = ncc(frameSmall, workW, x, y, layer)
                        if (score > second) second = score
                    }
                    x += stride
                }
                y += stride
            }
        }
        if (second != Float.NEGATIVE_INFINITY && top.score - second < config.ambiguityMargin) {
            return null
        }

        val inv = 1f / factor
        return Match(
            score = top.score,
            left = (top.x * inv).roundToInt(),
            top = (top.y * inv).roundToInt(),
            width = (top.w * inv).roundToInt(),
            height = (top.h * inv).roundToInt(),
            scale = top.scale
        )
    }

    private class Candidate(
        val score: Float, val x: Int, val y: Int, val w: Int, val h: Int, val scale: Float
    )

    private class Layer(
        val tpl: FloatArray, val w: Int, val h: Int, val stats: Stats, val scale: Float
    )

    private class Stats(val mean: Float, val sumSqDev: Float)

    private fun grayscale(frame: Frame): FloatArray {
        val out = FloatArray(frame.width * frame.height)
        for (i in out.indices) {
            val c = frame.pixels[i]
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            out[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return out
    }

    private fun stats(tpl: FloatArray): Stats {
        var sum = 0f
        for (v in tpl) sum += v
        val mean = sum / tpl.size
        var dev = 0f
        for (v in tpl) {
            val d = v - mean
            dev += d * d
        }
        return Stats(mean, dev)
    }

    private fun ncc(frame: FloatArray, fw: Int, fx: Int, fy: Int, layer: Layer): Float {
        var sumW = 0f
        var sumW2 = 0f
        var sumWT = 0f
        var ti = 0
        for (ty in 0 until layer.h) {
            var fi = (fy + ty) * fw + fx
            for (tx in 0 until layer.w) {
                val w = frame[fi]
                val t = layer.tpl[ti]
                sumW += w
                sumW2 += w * w
                sumWT += w * t
                fi++
                ti++
            }
        }
        val n = (layer.w * layer.h).toFloat()
        val meanW = sumW / n
        val varW = sumW2 - n * meanW * meanW
        if (varW <= 1e-3f) return -1f
        val numerator = sumWT - n * meanW * layer.stats.mean
        val denom = sqrt(varW * layer.stats.sumSqDev)
        if (denom <= 1e-6f) return -1f
        return (numerator / denom).coerceIn(-1f, 1f)
    }

    private fun downscale(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (dw == sw && dh == sh) return src.copyOf()
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val sy0 = (y.toLong() * sh / dh).toInt()
            val sy1 = (((y + 1).toLong() * sh + dh - 1) / dh).toInt().coerceAtMost(sh).coerceAtLeast(sy0 + 1)
            for (x in 0 until dw) {
                val sx0 = (x.toLong() * sw / dw).toInt()
                val sx1 = (((x + 1).toLong() * sw + dw - 1) / dw).toInt().coerceAtMost(sw).coerceAtLeast(sx0 + 1)
                var sum = 0f
                var count = 0
                var yy = sy0
                while (yy < sy1) {
                    var idx = yy * sw + sx0
                    var xx = sx0
                    while (xx < sx1) {
                        sum += src[idx]
                        count++
                        idx++
                        xx++
                    }
                    yy++
                }
                out[y * dw + x] = if (count > 0) sum / count else 0f
            }
        }
        return out
    }
}
