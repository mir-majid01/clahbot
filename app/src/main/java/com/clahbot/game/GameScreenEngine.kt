package com.clahbot.game

import android.graphics.Bitmap
import com.clahbot.ocr.BitmapIconColorSampler
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.OcrLine
import com.clahbot.ocr.OcrPage
import com.clahbot.ocr.ResultsScreenParser
import com.clahbot.results.ScreenHash
import kotlin.math.max
import kotlin.math.min

/**
 * Classifies a captured game frame into a [GameState] using text anchors in
 * screen fractions (resolution independent) plus colour/shape anchors (red
 * close X, wide green confirm, gem icons, dimmed scrim). OCR is injected as
 * an [OcrPage] whose boxes share the bitmap's pixel space.
 */
class GameScreenEngine {

    fun inspect(
        bitmap: Bitmap,
        page: OcrPage?,
        foregroundPackage: String?
    ): ScreenObservation {
        val frameW = bitmap.width
        val frameH = bitmap.height
        val (meanValue, meanSat) = sampleMeanColor(bitmap)
        val lines = page?.lines.orEmpty().mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            TextLine(
                text = normalize(line.text),
                left = box.left.toFloat() / frameW,
                top = box.top.toFloat() / frameH,
                right = box.right.toFloat() / frameW,
                bottom = box.bottom.toFloat() / frameH
            )
        }
        val base = ScreenObservation(
            state = GameState.UNKNOWN,
            confidence = 0.1f,
            lines = lines,
            frameWidth = frameW,
            frameHeight = frameH,
            hash = ScreenHash.fromBitmap(bitmap),
            meanValue = meanValue,
            meanSaturation = meanSat
        )

        if (foregroundPackage != null && !foregroundPackage.startsWith(CLASH_PACKAGE_PREFIX)) {
            return base.copy(state = GameState.NOT_CLASH, confidence = 1f)
        }

        val elements = detectElements(bitmap, lines)
        val observation = classify(base, lines, elements, meanValue, meanSat)
        if (observation.state != GameState.POPUP) {
            return observation
        }
        val safeTargets = buildSafeTargets(elements, frameW, frameH)
        return observation.copy(
            closeButton = elements.closeButton,
            confirmButton = elements.confirmButton,
            safeTargets = safeTargets,
            forbidden = elements.forbidden
        )
    }

    // ---------- classification ----------

    private fun classify(
        base: ScreenObservation,
        lines: List<TextLine>,
        elements: Elements,
        meanValue: Float,
        meanSat: Float
    ): ScreenObservation {
        dialogPopupKind(lines)?.let { (kind, confidence) ->
            return base.copy(state = GameState.POPUP, confidence = confidence, popupKind = kind)
        }

        var battle = 0f
        if (lines.any { it.text.contains("surrender") && it.left < 0.30f && it.top > 0.70f }) battle += 0.50f
        if (lines.any { it.text.contains("battleendsin") && it.top < 0.18f }) battle += 0.35f
        if (lines.any { it.text.contains("availableloot") && it.left < 0.35f && it.top < 0.35f }) battle += 0.25f
        if (battle >= 0.5f) return base.copy(state = GameState.BATTLE, confidence = min(0.95f, battle))

        resultsState(base)?.let { return it }

        var searching = 0f
        if (lines.any {
                it.text.contains("searching") &&
                    it.centerX in 0.25f..0.75f && it.centerY in 0.30f..0.70f
            }
        ) searching += 0.60f
        if (meanValue > 0.82f && meanSat < 0.10f) searching += 0.30f
        if (searching >= 0.6f) return base.copy(state = GameState.SEARCHING, confidence = min(0.9f, searching))

        var menu = 0f
        if (lines.any { it.text.contains("findamatch") && it.left < 0.60f }) menu += 0.55f
        if (lines.any {
                it.text.contains("multiplayer") || it.text.contains("singleplayer") ||
                    it.text.contains("practice")
            }
        ) menu += 0.25f
        if (lines.any { it.text.contains("shop") && it.left > 0.70f && it.top in 0.25f..0.75f }) menu += 0.15f
        if (menu >= 0.55f) return base.copy(state = GameState.ATTACK_MENU, confidence = min(0.9f, menu))

        var home = 0f
        if (lines.any { it.text.contains("battle") && it.left < 0.30f && it.top > 0.72f }) home += 0.45f
        if (lines.any { it.text.contains("build") && it.left > 0.70f && it.top > 0.72f }) home += 0.45f
        if (home >= 0.45f) return base.copy(state = GameState.HOME, confidence = min(0.9f, home))

        if (meanValue < 0.08f) return base.copy(state = GameState.LOADING, confidence = 0.8f)
        if (lines.any { it.text.contains("loading") && it.centerY in 0.25f..0.75f }) {
            return base.copy(state = GameState.LOADING, confidence = 0.7f)
        }

        var popup = 0f
        if (elements.scrim) popup += 0.15f
        if (elements.closeButton != null) popup += 0.35f
        if (elements.confirmButton != null) popup += 0.25f
        if (popup >= 0.5f) {
            return base.copy(
                state = GameState.POPUP,
                confidence = min(0.75f, popup),
                popupKind = PopupKind.GENERIC
            )
        }

        return base
    }

    private fun resultsState(base: ScreenObservation): ScreenObservation? {
        val frameW = base.frameWidth
        val frameH = base.frameHeight
        if (frameW <= 0 || base.lines.isEmpty()) return null
        val page = OcrPage(
            width = frameW,
            height = frameH,
            lines = base.lines.map { line ->
                OcrLine(
                    text = line.text,
                    box = OcrBox(
                        left = (line.left * frameW).toInt(),
                        top = (line.top * frameH).toInt(),
                        right = (line.right * frameW).toInt(),
                        bottom = (line.bottom * frameH).toInt()
                    ),
                    elements = emptyList()
                )
            }
        )
        val inspection = ResultsScreenParser(NoopIconColorSampler).inspect(page) ?: return null
        var confidence = 0.85f
        if (inspection.bonusPanel) confidence += 0.05f
        return base.copy(state = GameState.RESULTS, confidence = confidence)
    }

    // ---------- dialog text anchors ----------

    private fun dialogPopupKind(lines: List<TextLine>): Pair<PopupKind, Float>? {
        val dialog = lines.filter {
            it.left in 0.12f..0.88f && it.top in 0.12f..0.82f
        }
        if (dialog.isEmpty()) return null

        fun hasAny(vararg keys: String): Boolean =
            dialog.any { line -> keys.any { key -> line.text.contains(key) } }

        return when {
            hasAny("reload", "connectionlost", "disconnected") ->
                PopupKind.CONNECTION_LOST to 0.9f
            hasAny("wanttosurrender") ->
                PopupKind.SURRENDER_CONFIRM to 0.9f
            hasAny("wanttoquit", "exitgame", "wanttoleave") ->
                PopupKind.EXIT_CONFIRM to 0.9f
            dialog.any { it.text.contains("gem") } ->
                PopupKind.GEM_SPEND to 0.85f
            hasAny("rateus") ->
                PopupKind.RATE_OR_SEASON to 0.85f
            hasAny("season", "specialoffer", "leagueoffer") ->
                PopupKind.RATE_OR_SEASON to 0.7f
            hasAny("offer", "deal") ->
                PopupKind.SHOP_OR_OFFER to 0.75f
            else -> null
        }
    }

    // ---------- colour/shape elements ----------

    private class Elements(
        val closeButton: ScreenBox?,
        val confirmButton: ScreenBox?,
        val scrim: Boolean,
        val forbidden: List<ScreenBox>,
        val captionLines: List<Pair<String, TextLine>>
    )

    private fun detectElements(bitmap: Bitmap, lines: List<TextLine>): Elements {
        val small = Bitmap.createScaledBitmap(bitmap, BLOB_WIDTH, BLOB_HEIGHT, true)
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        val scaleX = bitmap.width.toFloat() / w
        val scaleY = bitmap.height.toFloat() / h
        fun toFrameBox(left: Int, top: Int, right: Int, bottom: Int): ScreenBox = ScreenBox(
            left = (left * scaleX).toInt().coerceAtMost(bitmap.width),
            top = (top * scaleY).toInt().coerceAtMost(bitmap.height),
            right = (right * scaleX).toInt().coerceAtMost(bitmap.width),
            bottom = (bottom * scaleY).toInt().coerceAtMost(bitmap.height)
        )

        val redBlobs = blobs(pixels, w, h, minCells = 2) { r, g, b -> r > 150 && g < 95 && b < 95 }
        val greenBlobs = blobs(pixels, w, h, minCells = MIN_GREEN_CELLS) { r, g, b ->
            g > 140 && r < 120 && b < 120
        }

        fun BlobBox.pixelWidth(): Int = ((right - left) * scaleX).toInt()
        fun BlobBox.pixelHeight(): Int = ((bottom - top) * scaleY).toInt()

        val closeButton = redBlobs
            .filter { blob ->
                val pw = blob.pixelWidth()
                val ph = blob.pixelHeight()
                pw > 0 && ph > 0 &&
                    pw >= bitmap.width * 0.012f && pw <= bitmap.width * 0.06f &&
                    pw.toFloat() / ph in 0.5f..2.0f
            }
            .minByOrNull { it.left + it.top }
            ?.let { toFrameBox(it.left, it.top, it.right, it.bottom) }

        val confirmButton = greenBlobs
            .filter { blob ->
                val pw = blob.pixelWidth()
                val ph = blob.pixelHeight()
                pw > 0 && ph > 0 &&
                    pw >= bitmap.width * 0.08f && pw <= bitmap.width * 0.40f &&
                    pw.toFloat() / ph >= 1.4f
            }
            .maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
            ?.let { toFrameBox(it.left, it.top, it.right, it.bottom) }

        val gemBoxes = greenBlobs
            .filter { blob ->
                val pw = blob.pixelWidth()
                val ph = blob.pixelHeight()
                pw > 0 && ph > 0 &&
                    pw >= bitmap.width * 0.008f && pw <= bitmap.width * 0.055f &&
                    pw.toFloat() / ph in 0.45f..1.7f
            }
            .map { toFrameBox(it.left, it.top, it.right, it.bottom) }

        val scrim = isScrimmed(pixels, w, h)

        val forbidden = mutableListOf<ScreenBox>()
        val captionLines = mutableListOf<Pair<String, TextLine>>()
        for (line in lines) {
            val frameLine = ScreenBox(
                left = (line.left * bitmap.width).toInt(),
                top = (line.top * bitmap.height).toInt(),
                right = (line.right * bitmap.width).toInt(),
                bottom = (line.bottom * bitmap.height).toInt()
            )
            if (NEVER_CAPTIONS.any { line.text.contains(it) }) {
                forbidden += frameLine.expanded(
                    marginX = bitmap.width / 64,
                    marginY = bitmap.height / 40,
                    frameW = bitmap.width,
                    frameH = bitmap.height
                )
            }
            for (caption in CAPTION_PRIORITY) {
                if (line.text.contains(caption)) {
                    captionLines += caption to line
                    break
                }
            }
        }
        for (gem in gemBoxes) {
            forbidden += gem.expanded(
                marginX = gem.width,
                marginY = gem.height,
                frameW = bitmap.width,
                frameH = bitmap.height
            )
        }

        return Elements(
            closeButton = closeButton,
            confirmButton = confirmButton,
            scrim = scrim,
            forbidden = forbidden.distinct(),
            captionLines = captionLines
        )
    }

    // ---------- safe dismiss targets ----------

    private fun buildSafeTargets(
        elements: Elements,
        frameW: Int,
        frameH: Int
    ): List<ScreenBox> {
        val targets = mutableListOf<ScreenBox>()
        elements.closeButton?.let { targets += it }
        for (caption in CAPTION_PRIORITY) {
            elements.captionLines
                .firstOrNull { it.first == caption }
                ?.let { (_, line) ->
                    targets += ScreenBox(
                        left = (line.left * frameW).toInt(),
                        top = (line.top * frameH).toInt(),
                        right = (line.right * frameW).toInt(),
                        bottom = (line.bottom * frameH).toInt()
                    ).expanded(
                        marginX = frameW / 64,
                        marginY = frameH / 40,
                        frameW = frameW,
                        frameH = frameH
                    )
                }
        }
        return targets
            .filter { target -> elements.forbidden.none { it.intersects(target) } }
            .distinct()
    }

    // ---------- grid helpers ----------

    private data class BlobBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun blobs(
        pixels: IntArray,
        w: Int,
        h: Int,
        minCells: Int,
        matches: (r: Int, g: Int, b: Int) -> Boolean
    ): List<BlobBox> {
        val grid = BooleanArray(w * h)
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            if (matches(r, g, b)) grid[index] = true
        }
        val visited = BooleanArray(w * h)
        val result = mutableListOf<BlobBox>()
        val stack = IntArray(w * h)
        for (start in grid.indices) {
            if (!grid[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var minCol = w
            var maxCol = -1
            var minRow = h
            var maxRow = -1
            var size = 0
            while (sp > 0) {
                val index = stack[--sp]
                val col = index % w
                val row = index / w
                size++
                if (col < minCol) minCol = col
                if (col > maxCol) maxCol = col
                if (row < minRow) minRow = row
                if (row > maxRow) maxRow = row
                if (col > 0 && grid[index - 1] && !visited[index - 1]) {
                    visited[index - 1] = true
                    stack[sp++] = index - 1
                }
                if (col < w - 1 && grid[index + 1] && !visited[index + 1]) {
                    visited[index + 1] = true
                    stack[sp++] = index + 1
                }
                if (row > 0 && grid[index - w] && !visited[index - w]) {
                    visited[index - w] = true
                    stack[sp++] = index - w
                }
                if (row < h - 1 && grid[index + w] && !visited[index + w]) {
                    visited[index + w] = true
                    stack[sp++] = index + w
                }
            }
            if (size >= minCells) {
                result += BlobBox(minCol, minRow, maxCol + 1, maxRow + 1)
            }
        }
        return result
    }

    private fun isScrimmed(pixels: IntArray, w: Int, h: Int): Boolean {
        var ringSum = 0L
        var ringCount = 0
        var centreSum = 0L
        var centreCount = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val color = pixels[y * w + x]
                val value = maxOf(
                    (color shr 16) and 0xff,
                    (color shr 8) and 0xff,
                    color and 0xff
                ) / 255f
                val fx = x.toFloat() / w
                val fy = y.toFloat() / h
                val isRing = fx < 0.06f || fx > 0.94f || fy < 0.06f || fy > 0.94f
                val isCentre = fx in 0.30f..0.70f && fy in 0.25f..0.75f
                if (isRing) {
                    ringSum += (value * 100).toInt()
                    ringCount++
                } else if (isCentre) {
                    centreSum += (value * 100).toInt()
                    centreCount++
                }
            }
        }
        if (ringCount == 0 || centreCount == 0) return false
        val ring = ringSum.toFloat() / ringCount / 100f
        val centre = centreSum.toFloat() / centreCount / 100f
        return ring < 0.55f && centre - ring > 0.12f
    }

    private fun sampleMeanColor(bitmap: Bitmap): Pair<Float, Float> {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 36, true)
        val pixels = IntArray(64 * 36)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 36)
        if (small !== bitmap) small.recycle()
        var valueSum = 0f
        var satSum = 0f
        for (color in pixels) {
            val r = ((color shr 16) and 0xff) / 255f
            val g = ((color shr 8) and 0xff) / 255f
            val b = (color and 0xff) / 255f
            val max = maxOf(r, g, b)
            val minV = minOf(r, g, b)
            valueSum += max
            satSum += if (max == 0f) 0f else (max - minV) / max
        }
        val count = pixels.size.toFloat()
        return valueSum / count to satSum / count
    }

    companion object {
        const val CLASH_PACKAGE_PREFIX = "com.supercell"
        private const val BLOB_WIDTH = 240
        private const val BLOB_HEIGHT = 135
        private const val MIN_GREEN_CELLS = 2

        private val CAPTION_PRIORITY = listOf(
            "cancel", "later", "close", "okay", "confirm", "reload"
        )

        private val NEVER_CAPTIONS = listOf(
            "buy", "surrender", "endbattle", "quit", "exit", "charge", "topup", "gems"
        )

        fun normalize(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() }

        private object NoopIconColorSampler : com.clahbot.ocr.IconColorSampler {
            override fun classify(numberBox: OcrBox) = null
        }
    }
}
