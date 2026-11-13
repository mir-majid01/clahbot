package com.clahbot.walls

import com.clahbot.game.GameInput
import com.clahbot.game.GameScreenEngine
import com.clahbot.game.ScreenBox
import com.clahbot.ocr.BitmapIconColorSampler
import com.clahbot.results.AppResultsReader
import com.clahbot.service.ClahAccessibilityService
import kotlinx.coroutines.delay

/**
 * The real Android [WallDevice]: it connects the JVM-proven Auto Walls 2.0
 * pipeline to the shared capture/OCR/classify pass, the accessibility gesture
 * dispatcher and a list scroll. This is the only untested glue — every stage
 * above it is unit-tested against a fake device, and this class makes no
 * decisions of its own.
 *
 * Production keeps [AutoWallsConfig.allowTapping] false, so the machine stops at
 * SAFETY_BLOCKED before any tap. [observe] is safe to run, but [tap] and [scroll]
 * are only reached once the user enables tapping in the controller and the
 * OCR quality has been device-verified; the [AutoWallsRuntime.run] loop is
 * wired only to the trigger boundary with the gate held around it.
 */
class AutoWallsDevice(
    private val engine: GameScreenEngine,
    private val reader: AppResultsReader
) : WallDevice {

    /** The frame the last [observe] produced; taps map through its window bounds. */
    private var lastFrame: ClahAccessibilityService.GameFrame? = null

    override suspend fun observe(): WallFrame? {
        val frame = ClahAccessibilityService.captureGameFrame() ?: return null
        lastFrame = frame
        val page = runCatching { reader.page(frame.bitmap) }.getOrNull() ?: return null
        val observation = engine.inspect(
            frame.bitmap,
            page,
            ClahAccessibilityService.currentForegroundPackage()
        )
        val sampler = BitmapIconColorSampler(frame.bitmap)
        return WallFrame(
            observation = observation,
            lines = page.lines,
            width = frame.bitmap.width,
            height = frame.bitmap.height,
            classify = { box -> sampler.classify(box) }
        )
    }

    override suspend fun tap(box: ScreenBox): Boolean {
        val frame = lastFrame ?: return false
        return GameInput.tapBox(box, frame)
    }

    /**
     * Swipe up the centre of the captured frame to reveal lower wall rows. A
     * completed gesture is not proof the list moved: the runtime re-scans and
     * re-selects from the fresh frame, never replaying a stale coordinate.
     */
    override suspend fun scroll(): Boolean {
        val frame = lastFrame ?: return false
        val bitmap = frame.bitmap
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val x = bitmap.width / 2
        val from = GameInput.toScreen(x, (bitmap.height * SCROLL_FROM_FRACTION).toInt(), frame)
        val to = GameInput.toScreen(x, (bitmap.height * SCROLL_TO_FRACTION).toInt(), frame)
        return GameInput.swipe(from.x, from.y, to.x, to.y, SCROLL_SWIPE_MS)
    }

    override suspend fun wait(ms: Long) {
        if (ms > 0L) delay(ms)
    }

    override fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val SCROLL_FROM_FRACTION = 0.68f
        private const val SCROLL_TO_FRACTION = 0.38f
        private const val SCROLL_SWIPE_MS = 350L
    }
}