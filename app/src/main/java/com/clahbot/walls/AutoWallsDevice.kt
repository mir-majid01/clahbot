package com.clahbot.walls

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.clahbot.data.AppEventLog
import com.clahbot.game.GameInput
import com.clahbot.game.GameScreenEngine
import com.clahbot.game.ScreenBox
import com.clahbot.gesture.CancelToken
import com.clahbot.gesture.GestureAuthority
import com.clahbot.gesture.GestureCommand
import com.clahbot.gesture.GesturePriority
import com.clahbot.gesture.GestureResult
import com.clahbot.gesture.ScreenOwnership
import com.clahbot.ocr.BitmapBuilderFaceSampler
import com.clahbot.ocr.BitmapIconColorSampler
import com.clahbot.results.AppResultsReader
import com.clahbot.results.ScreenHash
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The real Android [WallDevice]: it connects the JVM-proven Auto Walls 2.0
 * pipeline to the shared capture/OCR/classify pass, the accessibility gesture
 * dispatcher (through [GestureAuthority]) and a list scroll. This is the only
 * untested glue — every stage above it is unit-tested against a fake device,
 * and this class makes no decisions of its own.
 *
 * Production keeps [AutoWallsConfig.allowTapping] false, so the machine stops at
 * SAFETY_BLOCKED before any tap. [observe] is safe to run, but [tap] and [scroll]
 * are only reached once the user enables tapping in the controller and the
 * OCR quality has been device-verified; the [AutoWallsRuntime.run] loop is
 * wired only to the trigger boundary with the gate held around it.
 */
class AutoWallsDevice(
    private val engine: GameScreenEngine,
    private val reader: AppResultsReader,
    private val gestureAuthority: GestureAuthority? = null,
    private val sessionId: Long = 0L
) : WallDevice {

    /** The frame the last [observe] produced; taps map through its window bounds. */
    private var lastFrame: ClahAccessibilityService.GameFrame? = null

    /** Stable transaction id shared by every wall-upgrade gesture in this upgrade. */
    private val wallTxnId: Long = sessionId

    /** True once startWallTxn has been called for this upgrade. */
    private var txnStarted = false

    /** Monotonic transaction id for the read-only scan swipes. */
    private var swipeTransactionId = 0L

    /** Id of the scan that owns the screen right now (0 when idle). */
    override var scanOwnerId: Long = 0L

    private val gameInput = GameInput(gestureAuthority ?: GestureAuthority.shared(), sessionId)

    override suspend fun observe(): WallFrame? {
        val frame = ClahAccessibilityService.captureGameFrame() ?: return null
        lastFrame = frame
        val startedAt = System.currentTimeMillis()
        val page = runCatching { reader.page(frame.bitmap) }.getOrNull() ?: return null
        val observation = engine.inspect(
            frame.bitmap,
            page,
            ClahAccessibilityService.currentForegroundPackage()
        )
        val sampler = BitmapIconColorSampler(frame.bitmap)
        val faceSampler = BitmapBuilderFaceSampler(frame.bitmap)
        // Second, upscaled OCR pass over the fields the full-frame pass could not
        // read (level badges, prices) and over the builder strip. This is the
        // fix for the weak field, not a lower acceptance gate: the extra lines go
        // through exactly the same WallDiscovery rules.
        val lines = enrichLines(frame.bitmap, page.lines, observation, sampler)
        val wallFrame = WallFrame(
            observation = observation,
            lines = lines,
            width = frame.bitmap.width,
            height = frame.bitmap.height,
            classify = { box -> sampler.classify(box) },
            capturedAtMillis = frame.capturedAtMillis
        )
        wallFrame.builderFaceAt = { box -> faceSampler.classify(box) }
        wallFrame.builderFaceDetail = { faceSampler.diagnostic() }
        AppEventLog.log(
            "auto-walls-2.0 observe frame=${frame.bitmap.width}x${frame.bitmap.height} " +
                "lines=${page.lines.size}->${lines.size} ocrMs=${System.currentTimeMillis() - startedAt}"
        )
        return wallFrame
    }

    /**
     * Adds the lines of the targeted re-reads to the full-frame OCR lines.
     *
     * Two regions are re-read because ML Kit misses them on a whole-frame pass:
     *  - a wall row's level badge and price, so a visible `Wall x 12` row can
     *    actually reach IDENTIFIED instead of sitting at "level missing";
     *  - the builder strip, when fewer than two `free/total` badges were read,
     *    because a missed left-most badge made the list view report the
     *    laboratory's `1/1` as the builder count.
     *
     * Every re-read is logged with the region, the raw text and whether it
     * helped. Nothing is guessed: a region that still yields no digits adds no
     * line, so the row stays UNCERTAIN.
     */
    private suspend fun enrichLines(
        bitmap: android.graphics.Bitmap,
        lines: List<com.clahbot.ocr.OcrLine>,
        observation: com.clahbot.game.ScreenObservation,
        sampler: BitmapIconColorSampler
    ): List<com.clahbot.ocr.OcrLine> {
        if (lines.isEmpty()) return lines
        val extra = ArrayList<com.clahbot.ocr.OcrLine>()
        val currencyAt: (com.clahbot.ocr.OcrBox) -> String? = { box ->
            when (sampler.classify(box)) {
                com.clahbot.ocr.ResourceKind.GOLD -> "gold"
                com.clahbot.ocr.ResourceKind.ELIXIR -> "elixir"
                com.clahbot.ocr.ResourceKind.DARK -> "dark"
                null -> null
            }
        }
        val gaps = runCatching {
            WallDiscovery.incompleteRows(lines, bitmap.height, currencyAt = currencyAt)
        }.getOrElse { emptyList() }
        for (gap in gaps.take(MAX_FIELD_REREADS)) {
            if (gap.needLevel) {
                val region = WallDiscovery.levelBadgeRegion(gap.titleBox)
                val text = readRegionText(bitmap, region)
                val level = firstSmallNumber(text)
                AppEventLog.log(
                    "auto-walls-2.0 reread level row=\"${gap.title}\" region=${region.format()} " +
                        "raw=\"${text ?: "-"}\" -> ${level ?: "none"}"
                )
                if (level != null) {
                    extra += com.clahbot.ocr.OcrLine(level.toString(), region, emptyList())
                }
            }
            if (gap.needPrice) {
                val region = WallDiscovery.priceRegion(gap.titleBox)
                val text = readRegionText(bitmap, region)
                AppEventLog.log(
                    "auto-walls-2.0 reread price row=\"${gap.title}\" region=${region.format()} " +
                        "raw=\"${text ?: "-"}\""
                )
                if (!text.isNullOrBlank()) {
                    extra += com.clahbot.ocr.OcrLine(text, region, emptyList())
                }
            }
        }

        // Builder strip: only when the full-frame pass clearly under-read it.
        val strip = WallDiscovery.builderStripRegion(bitmap.width, bitmap.height)
        val stripFractions = lines.count { line ->
            val box = line.box ?: return@count false
            box.centerX >= strip.left && box.centerY >= strip.top &&
                FRACTION.containsMatchIn(line.text)
        }
        if (stripFractions < MIN_STRIP_FRACTIONS) {
            val reread = readRegionLines(bitmap, strip)
            AppEventLog.log(
                "auto-walls-2.0 reread builder-strip region=${strip.format()} " +
                    "fullFrameFractions=$stripFractions rereadLines=${reread.size} " +
                    "text=[${reread.joinToString(" | ") { it.text }}]"
            )
            extra += reread
        }
        if (extra.isEmpty()) return lines
        AppEventLog.log("auto-walls-2.0 reread added=${extra.size} lines")
        return lines + extra
    }

    /** One upscaled OCR pass over [region], returning the raw joined text. */
    private suspend fun readRegionText(
        bitmap: android.graphics.Bitmap,
        region: com.clahbot.ocr.OcrBox
    ): String? {
        val lines = readRegionLines(bitmap, region)
        val text = lines.joinToString(" ") { it.text }.trim()
        return text.ifBlank { null }
    }

    /**
     * Crops [region], upscales it [REREAD_SCALE]x with bilinear filtering and
     * runs OCR on the crop. The returned boxes are mapped back into full-frame
     * coordinates so the existing row clustering and pairing still apply.
     */
    private suspend fun readRegionLines(
        bitmap: android.graphics.Bitmap,
        region: com.clahbot.ocr.OcrBox
    ): List<com.clahbot.ocr.OcrLine> {
        val left = region.left.coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
        val top = region.top.coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
        val width = (region.right.coerceAtMost(bitmap.width) - left).coerceAtLeast(1)
        val height = (region.bottom.coerceAtMost(bitmap.height) - top).coerceAtLeast(1)
        if (width < 4 || height < 4) return emptyList()
        val crop = runCatching { android.graphics.Bitmap.createBitmap(bitmap, left, top, width, height) }
            .getOrNull() ?: return emptyList()
        val scaled = runCatching {
            android.graphics.Bitmap.createScaledBitmap(
                crop, width * REREAD_SCALE, height * REREAD_SCALE, true
            )
        }.getOrNull()
        if (scaled == null) {
            crop.recycle()
            return emptyList()
        }
        // Small game text is white-on-dark and anti-aliased at native size; the
        // upscale alone can leave low-contrast edges. Grayscale + mild contrast
        // is applied only to this OCR crop, never to the full frame, so the
        // colour icon classification still sees real pixels.
        val prepared = runCatching { enhanceForOcr(scaled) }.getOrNull() ?: scaled
        val page = runCatching { reader.page(prepared) }.getOrNull()
        if (prepared !== scaled) prepared.recycle()
        if (scaled !== crop) scaled.recycle()
        crop.recycle()
        return page?.lines.orEmpty().mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            com.clahbot.ocr.OcrLine(
                text = line.text,
                box = com.clahbot.ocr.OcrBox(
                    left + box.left / REREAD_SCALE,
                    top + box.top / REREAD_SCALE,
                    left + box.right / REREAD_SCALE,
                    top + box.bottom / REREAD_SCALE
                ),
                elements = emptyList()
            )
        }
    }

    /**
     * Grayscale + mild contrast for a small OCR crop. The game's HUD is white
     * text on a dark, semi-transparent panel; desaturating removes coloured
     * banners that bleed into the glyphs and a small contrast lift separates
     * the antialiased edges from the background. Returns a new bitmap (the
     * caller recycles it); on any failure the caller falls back to the plain
     * upscaled crop, so this can only ever change OCR input, never abort a read.
     */
    private fun enhanceForOcr(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val contrast = ColorMatrix(
            floatArrayOf(
                CONTRAST, 0f, 0f, 0f, CONTRAST_OFFSET,
                0f, CONTRAST, 0f, 0f, CONTRAST_OFFSET,
                0f, 0f, CONTRAST, 0f, CONTRAST_OFFSET,
                0f, 0f, 0f, 1f, 0f
            )
        )
        gray.postConcat(contrast)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(gray) }
        Canvas(out).drawBitmap(source, 0f, 0f, paint)
        return out
    }

    /** The first 1-2 digit number in [text], or null. */
    private fun firstSmallNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = SMALL_NUMBER.find(text) ?: return null
        return match.value.toIntOrNull()
    }

    override suspend fun tap(box: ScreenBox): Boolean {
        ensureWallTxn()
        val frame = lastFrame ?: return false
        return gameInput.tapBox(
            box, frame, null, wallTxnId,
            owner = "AutoWallsDevice",
            reason = "wall-upgrade-tap"
        )
    }

    /** Starts the wall transaction on the first gesture of an upgrade. */
    private fun ensureWallTxn() {
        if (!txnStarted) {
            txnStarted = gestureAuthority?.startWallTxn(sessionId) == true
        }
    }

    override fun endWallTxn() {
        if (txnStarted) {
            gestureAuthority?.endWallTxn()
            txnStarted = false
        }
    }

    /**
     * Bounded reverse swipes that return the list to the position the scan
     * started from. The scanner calls this from a NonCancellable finally block,
     * so the list is restored even when the scan is cancelled mid-way. Like
     * [scroll] it only swipes — it never taps — and it goes through the shared
     * [GestureAuthority] so the dispatch gate sees it.
     */
    override suspend fun scrollBack(steps: Int): Int {
        val frame = lastFrame ?: return 0
        val bitmap = frame.bitmap
        if (bitmap.width <= 0 || bitmap.height <= 0 || steps <= 0) return 0
        val x = scrollX(bitmap.width)
        // Restoring means moving the LIST CONTENT back up, which is a swipe
        // DOWNWARD on screen (top -> bottom).
        val from = GameInput.toScreen(x, (bitmap.height * SCROLL_TO_FRACTION).toInt(), frame)
        val to = GameInput.toScreen(x, (bitmap.height * SCROLL_FROM_FRACTION).toInt(), frame)
        var performed = 0
        repeat(steps) {
            val ok = dispatchScanSwipe(
                from.x, from.y, to.x, to.y, SCROLL_SWIPE_MS,
                reason = "scan-scroll-restore"
            )
            if (!ok) return performed
            performed++
            delay(SCROLL_SETTLE_MS)
        }
        return performed
    }

    /**
     * One corrective upward swipe used by the restore verification: a single
     * reverse swipe inside the list panel, dispatched through the shared
     * authority like every other scan gesture.
     */
    override suspend fun scrollUp(): Boolean {
        val frame = lastFrame ?: return false
        val bitmap = frame.bitmap
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val x = scrollX(bitmap.width)
        val from = GameInput.toScreen(x, (bitmap.height * SCROLL_TO_FRACTION).toInt(), frame)
        val to = GameInput.toScreen(x, (bitmap.height * SCROLL_FROM_FRACTION).toInt(), frame)
        return dispatchScanSwipe(
            from.x, from.y, to.x, to.y, SCROLL_SWIPE_MS,
            reason = "scan-scroll-correct-restore"
        )
    }

    /**
     * Swipe up the right-side list panel to reveal lower wall rows. A completed
     * gesture is not proof the list moved: the runtime re-scans and re-selects
     * from the fresh frame, never replaying a stale coordinate.
     *
     * The x coordinate is placed in the panel area (right side of the frame)
     * rather than the frame centre, because the Buildings & Heroes list panel
     * is on the right side of the landscape game screen. Swiping the centre
     * would hit the village map and scroll the camera instead.
     *
     * Every scan swipe is dispatched through the shared [GestureAuthority] with
     * owner `AutoWallsDevice` and a `scan-scroll` reason, so the single
     * dispatch point can tell it apart from a wall-upgrade tap.
     */
    override suspend fun scroll(): Boolean {
        val frame = lastFrame ?: return false
        val bitmap = frame.bitmap
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val x = scrollX(bitmap.width)
        val from = GameInput.toScreen(x, (bitmap.height * SCROLL_FROM_FRACTION).toInt(), frame)
        val to = GameInput.toScreen(x, (bitmap.height * SCROLL_TO_FRACTION).toInt(), frame)
        return dispatchScanSwipe(
            from.x, from.y, to.x, to.y, SCROLL_SWIPE_MS,
            reason = "scan-scroll"
        )
    }

    /**
     * Sends one read-only list scroll through the shared [GestureAuthority].
     *
     * The command carries the scan's owner id as its session id, so while the
     * scan holds the [ScanGate] the authority lets THIS swipe through and
     * refuses every other automation surface. With "Allow tapping" off the
     * authority also refuses anything from this owner that is not a
     * `scan-scroll` swipe.
     *
     * When no authority is wired (JVM tests, or a service that has not
     * connected yet) the direct accessibility path is used so the scan still
     * works; both paths write a GESTURE line.
     */
    private suspend fun dispatchScanSwipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
        reason: String
    ): Boolean {
        val frame = lastFrame
        val bitmap = frame?.bitmap
        val w = bitmap?.width ?: 0
        val h = bitmap?.height ?: 0
        AppEventLog.log(
            "auto-walls-2.0 scroll reason=$reason " +
                "frame=${w}x$h " +
                "from=($fromX,$fromY) to=($toX,$toY) dur=${durationMs}ms " +
                "fromFrac=(${frac(fromX, w)},${frac(fromY, h)}) " +
                "toFrac=(${frac(toX, w)},${frac(toY, h)}) " +
                "insideListPanel=${isInsideListPanel(fromX, w)}"
        )
        val authority = gestureAuthority
        if (authority == null) {
            return ClahAccessibilityService.swipeOnScreen(
                fromX, fromY, toX, toY, durationMs,
                owner = OWNER,
                reason = reason
            )
        }
        swipeTransactionId++
        val command = GestureCommand(
            kind = GestureCommand.Kind.Swipe(fromX, fromY, toX, toY, durationMs),
            sessionId = scanOwnerId,
            transactionId = swipeTransactionId,
            // The scroll is not tied to a specific screenshot: the list moves
            // between the capture and the swipe by design, so the freshness and
            // frame-hash checks do not apply.
            frameHash = "",
            captureTimestampMillis = 0L,
            expiresAtMillis = System.currentTimeMillis() + 5_000,
            priority = GesturePriority.SHARED,
            screenOwnership = ScreenOwnership.SHARED,
            cancelToken = CancelToken(),
            owner = OWNER,
            reason = reason
        )
        val result = authority.enqueueAndAwait(command, null)
        return result == GestureResult.Completed
    }

    /** Formats a pixel coordinate as a fraction of the frame size. */
    private fun frac(value: Int, size: Int): String =
        if (size <= 0) "-" else String.format(Locale.US, "%.3f", value.toFloat() / size)

    /**
     * True when the x coordinate is inside the right-hand list panel.
     * The Buildings & Heroes panel starts at roughly 55 % of the landscape
     * frame width; anything left of that is the village map, where a swipe
     * scrolls the CAMERA instead of the list.
     */
    private fun isInsideListPanel(x: Int, frameWidth: Int): Boolean =
        frameWidth > 0 && x >= (frameWidth * LIST_PANEL_LEFT_FRACTION).toInt()

    /**
     * Returns the horizontal pixel position for a scroll or restore swipe.
     * The Buildings & Heroes list occupies the right portion of the landscape
     * game frame; the x coordinate must fall inside the panel, not over the
     * village map. The fraction is chosen to centre the swipe in the list
     * panel area (approximately the right 45–55 % of the frame).
     */
    private fun scrollX(frameWidth: Int): Int =
        (frameWidth * SCROLL_X_FRACTION).toInt()

    override suspend fun wait(ms: Long) {
        if (ms > 0L) delay(ms)
    }

    override fun now(): Long = System.currentTimeMillis()

    companion object {
        /** Owner name every scan swipe is dispatched under. */
        private const val OWNER = "AutoWallsDevice"

        /** Scroll x as a fraction of frame width: inside the right-side list panel.
         *  The Buildings & Heroes panel starts at ~55-60 % of the landscape frame.
         *  This puts the swipe at ~75-78 % of the width, well within the panel
         *  and away from the village map on the left. */
        private const val SCROLL_X_FRACTION = 0.78f
        /** Top of the swipe arc as a fraction of frame height. */
        private const val SCROLL_FROM_FRACTION = 0.68f
        /** Bottom of the swipe arc as a fraction of frame height. */
        private const val SCROLL_TO_FRACTION = 0.38f
        /** Left edge of the list panel as a fraction of frame width. */
        private const val LIST_PANEL_LEFT_FRACTION = 0.55f
        /** Duration of one swipe in milliseconds. */
        private const val SCROLL_SWIPE_MS = 350L
        /** Pause between restore swipes so the list settles before the next one. */
        private const val SCROLL_SETTLE_MS = 500L

        /**
         * How many wall rows may have a field re-read per frame. A viewport holds
         * a handful of rows; the cap keeps one bad frame from turning into an
         * unbounded number of extra OCR passes.
         */
        private const val MAX_FIELD_REREADS = 6

        /**
         * The full-frame pass must have read at least this many builder-strip
         * fractions before the strip re-read is skipped. The strip normally shows
         * the regular builders AND the laboratory, so fewer than two means the
         * pass under-read it.
         */
        private const val MIN_STRIP_FRACTIONS = 2

        /** Upscale factor for the targeted field and strip re-reads. */
        private const val REREAD_SCALE = 3

        /** Contrast multiplier applied to the upscaled crop before OCR. */
        private const val CONTRAST = 1.15f

        /** Mid-grey pivot for the contrast matrix: 128 * (1 - CONTRAST). */
        private const val CONTRAST_OFFSET = -19.2f

        /** A level badge: one or two digits, not part of a longer number. */
        private val SMALL_NUMBER = Regex("(?<![0-9])([0-9]{1,2})(?![0-9])")

        /** A `free/total` builder fraction. */
        private val FRACTION = Regex("[0-9]\\s*/\\s*[0-9]")
    }
}