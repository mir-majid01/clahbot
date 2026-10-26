package com.clahbot.ocrlab

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Digit-shape reader. It thresholds the row crop on neutral-channel
 * whiteness, segments the glyph line, normalises each glyph to 24x32 and
 * matches it against [DigitTemplateStore]. Any ambiguous glyph makes the
 * whole reading unusable rather than guessed.
 */
class DigitTemplateEngine(private val store: DigitTemplateStore) {

    data class Read(
        val text: String,
        val value: Long?,
        val binary: Bitmap,
        val bounds: OcrBox?
    )

    private data class Component(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val area: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerY: Int get() = (top + bottom) / 2
    }

    fun read(source: Bitmap): Read {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val minValues = IntArray(width * height)
        val histogram = IntArray(256)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val min = min(red, min(green, blue))
            minValues[index] = min
            histogram[min]++
        }
        val threshold = otsu(histogram).coerceIn(95f, 175f)
        val mask = BooleanArray(width * height)
        val binaryPixels = IntArray(width * height)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val max = max(red, max(green, blue))
            val saturation = if (max == 0) 0f else (max - minValues[index]) / max.toFloat()
            val ink = saturation <= 0.55f && minValues[index] >= threshold
            mask[index] = ink
            binaryPixels[index] = if (ink) Color.WHITE else Color.BLACK
        }
        val binary = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        binary.setPixels(binaryPixels, 0, width, 0, 0, width, height)

        val components = components(mask, width, height)
        val glyphs = selectLine(components)
        if (glyphs.isEmpty()) {
            return Read("", null, binary, null)
        }

        val bounds = OcrBox(
            left = glyphs.minOf { it.left }.coerceIn(0, width - 1),
            top = glyphs.minOf { it.top }.coerceIn(0, height - 1),
            right = glyphs.maxOf { it.right }.coerceIn(1, width),
            bottom = glyphs.maxOf { it.bottom }.coerceIn(1, height)
        )

        if (glyphs.size > 12) {
            return Read("", null, binary, bounds)
        }
        val digits = StringBuilder()
        var ambiguous = false
        for (component in glyphs) {
            val glyph = normalize(minValues, width, component, threshold)
            val match = store.classify(glyph)
            if (match == null) {
                ambiguous = true
                digits.append('?')
            } else {
                digits.append(match.digit)
            }
        }
        val text = digits.toString()
        val value = if (ambiguous) null else text.toLongOrNull()
        return Read(text, value, binary, bounds)
    }

    private fun components(mask: BooleanArray, width: Int, height: Int): List<Component> {
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val result = mutableListOf<Component>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val start = y * width + x
                if (!mask[start] || seen[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = true
                var left = x
                var right = x
                var top = y
                var bottom = y
                var area = 0
                while (head < tail) {
                    val index = queue[head++]
                    val px = index % width
                    val py = index / width
                    area++
                    if (px < left) left = px
                    if (px > right) right = px
                    if (py < top) top = py
                    if (py > bottom) bottom = py
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = px + dx
                            val ny = py + dy
                            if (nx !in 0 until width || ny !in 0 until height) continue
                            val next = ny * width + nx
                            if (mask[next] && !seen[next]) {
                                seen[next] = true
                                queue[tail++] = next
                            }
                        }
                    }
                }
                if (area >= 12) {
                    result += Component(left, top, right + 1, bottom + 1, area)
                }
            }
        }
        return result
    }

    private fun selectLine(components: List<Component>): List<Component> {
        val tall = components.filter {
            it.width >= 2 && it.height >= 8 && it.width <= it.height * 1.35f
        }
        if (tall.isEmpty()) return emptyList()
        val maxHeight = tall.maxOf { it.height }
        val filtered = tall.filter {
            it.height >= maxHeight * 0.7f && it.height <= maxHeight * 1.2f
        }
        if (filtered.isEmpty()) return emptyList()
        val medianCenter = filtered.map { it.centerY }.sorted()[filtered.size / 2]
        val line = filtered.filter {
            kotlin.math.abs(it.centerY - medianCenter) <= maxHeight * 0.35f
        }.sortedBy { it.left }

        val merged = mutableListOf<Component>()
        for (component in line) {
            if (merged.isNotEmpty()) {
                val previous = merged.last()
                val overlap = min(previous.right, component.right) -
                    max(previous.left, component.left)
                val combinedHeight = max(previous.bottom, component.bottom) -
                    min(previous.top, component.top)
                val sameLine = kotlin.math.abs(component.centerY - previous.centerY) <=
                    max(previous.height, component.height) * 0.5f
                if (overlap >= 2 && sameLine && combinedHeight <= maxHeight * 1.2f) {
                    merged[merged.size - 1] = Component(
                        left = min(previous.left, component.left),
                        top = min(previous.top, component.top),
                        right = max(previous.right, component.right),
                        bottom = max(previous.bottom, component.bottom),
                        area = previous.area + component.area
                    )
                    continue
                }
            }
            merged += component
        }
        return merged
    }

    private fun normalize(
        minValues: IntArray,
        width: Int,
        component: Component,
        threshold: Float
    ): FloatArray {
        val output = FloatArray(DigitTemplateStore.W * DigitTemplateStore.H)
        val scale = min(
            (DigitTemplateStore.H - 2f) / component.height,
            (DigitTemplateStore.W - 2f) / component.width
        )
        val targetWidth = max(1, (component.width * scale).roundToInt())
        val targetHeight = max(1, (component.height * scale).roundToInt())
        val offsetX = (DigitTemplateStore.W - targetWidth) / 2
        val offsetY = (DigitTemplateStore.H - targetHeight) / 2
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                val sourceX = component.left +
                    (x * component.width / targetWidth).coerceAtMost(component.width - 1)
                val sourceY = component.top +
                    (y * component.height / targetHeight).coerceAtMost(component.height - 1)
                if (minValues[sourceY * width + sourceX] >= threshold) {
                    output[(offsetY + y) * DigitTemplateStore.W + offsetX + x] = 1f
                }
            }
        }
        return output
    }

    private fun otsu(histogram: IntArray): Float {
        var total = 0
        var sum = 0L
        for (value in 0 until 256) {
            total += histogram[value]
            sum += value.toLong() * histogram[value]
        }
        if (total == 0) return 128f
        var sumBackground = 0L
        var countBackground = 0
        var best = -1.0
        var bestThreshold = 128
        for (threshold in 0 until 256) {
            countBackground += histogram[threshold]
            if (countBackground == 0) continue
            val countForeground = total - countBackground
            if (countForeground == 0) break
            sumBackground += threshold.toLong() * histogram[threshold]
            val meanBackground = sumBackground.toDouble() / countBackground
            val meanForeground = (sum - sumBackground).toDouble() / countForeground
            val between = countBackground.toDouble() * countForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (between > best) {
                best = between
                bestThreshold = threshold
            }
        }
        return bestThreshold.toFloat()
    }
}
