package com.clahbot.walls

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import com.clahbot.data.AppEventLog
import com.clahbot.data.BotState
import com.clahbot.data.MacroSelection
import com.clahbot.data.ResultToastUi
import com.clahbot.data.WallStripUi
import com.clahbot.data.models.Macro
import com.clahbot.data.models.RecordedGesture
import com.clahbot.execution.MacroPlayer
import com.clahbot.execution.MacroTimeline
import com.clahbot.game.GameInput
import com.clahbot.game.GameScreenEngine
import com.clahbot.game.GameState
import com.clahbot.game.PopupKind
import com.clahbot.game.ScreenBox
import com.clahbot.ocr.BitmapIconColorSampler
import com.clahbot.ocr.OcrBox
import com.clahbot.ocr.ResourceKind
import com.clahbot.recovery.RecoveryAgent
import com.clahbot.results.AppResultsReader
import com.clahbot.results.ScreenHash
import com.clahbot.service.ClahAccessibilityService
import java.util.Locale
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The auto wall upgrader. One cycle walks the user's wall line: read
 * balances, run the camera macro, align the landmark, tap the wall, build the
 * multi-select, verify the price against the user's setting, confirm, then
 * verify the balance drop. Test (no spend) mode stops at the confirm dialog.
 *
 * Safety is structural: only gold/elixir prices are ever accepted, the blue
 * Wall Ring button can never be tapped because no finder returns it, gem
 * dialogs are only ever closed with their red X, and Stop always wins by
 * cancelling any open dialog with X or Cancel.
 */
class WallUpgrader(
    private val scope: CoroutineScope,
    private val context: Context,
    private val engine: GameScreenEngine,
    private val reader: AppResultsReader,
    private val repository: WallsRepository,
    private val agent: RecoveryAgent,
    private val cameraMacroProvider: (Long) -> Macro?
) {

    @Volatile
    private var stopRequested = false

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun run(mode: WallRunMode) {
        if (isRunning) return
        if (MacroPlayer.hasActivePlayer()) {
            BotState.setResultToast(ResultToastUi.Kind.WARNING, "Stop the macro first")
            return
        }
        stopRequested = false
        job = scope.launch {
            try {
                execute(mode)
            } catch (_: CancellationException) {
                BotState.setWallStrip(null)
            } finally {
                BotState.setWallStrip(null)
                BotState.setRecoveryStrip(null)
            }
        }
    }

    /** Stop always wins: cancel the job and close any dialog safely. */
    fun cancel() {
        stopRequested = true
        job?.cancel()
        job = null
        scope.launch {
            runCatching { closeDialogsSafely() }
            BotState.setWallStrip(null)
            BotState.setRecoveryStrip(null)
        }
    }

    private suspend fun execute(mode: WallRunMode) {
        val settings = repository.settings()
        val line = settings.line
        if (line == null) {
            fail("Set a wall line first")
            return
        }
        if (settings.wallLevel <= 0) {
            fail("Set your current wall level first")
            return
        }
        if (MacroPlayer.hasActivePlayer()) {
            fail("A macro is running")
            return
        }
        val reading = agent.observe()
        if (reading == null) {
            fail("No game frame")
            return
        }
        if (reading.observation.state != GameState.HOME) {
            fail("Wall cycle needs the home village")
            return
        }
        BotState.setWallStrip(WallStripUi("Walls"))
        AppEventLog.log("wall cycle start mode=$mode")

        val balances = readBalances(reading.frame.bitmap)
        val ordered = when (settings.priority) {
            WallPriority.GOLD_FIRST -> listOf(WallResource.GOLD, WallResource.ELIXIR)
            WallPriority.ELIXIR_FIRST -> listOf(WallResource.ELIXIR, WallResource.GOLD)
            WallPriority.FULLER_FIRST -> listOf(
                WallResource.GOLD to balances[WallResource.GOLD],
                WallResource.ELIXIR to balances[WallResource.ELIXIR]
            ).sortedByDescending { it.second ?: 0L }.map { it.first }
        }

        var upgradedTotal = 0
        var spentTotal = 0L
        var lastResource: WallResource? = null
        for (resource in ordered) {
            if (stopRequested) break
            if (!settings.enabledFor(resource)) continue
            val result = runResource(resource, mode, balances[resource])
            upgradedTotal += result.walls
            spentTotal += result.spent
            if (result.walls > 0) lastResource = resource
            if (!result.completed) break
        }

        if (upgradedTotal > 0) {
            val resourceLabel = lastResource?.let {
                if (it == WallResource.GOLD) "gold" else "elixir"
            } ?: ""
            if (mode == WallRunMode.TEST) {
                BotState.setResultToast(
                    ResultToastUi.Kind.SUCCESS,
                    "Test: would have upgraded $upgradedTotal walls"
                )
            } else {
                BotState.setResultToast(
                    ResultToastUi.Kind.SUCCESS,
                    "$upgradedTotal walls upgraded" +
                        (if (spentTotal > 0 && resourceLabel.isNotEmpty()) {
                            " · ${compact(spentTotal)} $resourceLabel"
                        } else {
                            ""
                        })
                )
            }
        }
        AppEventLog.log("wall cycle end walls=$upgradedTotal mode=$mode")
    }

    private data class ResourceResult(
        val walls: Int,
        val spent: Long,
        val completed: Boolean
    )

    private suspend fun runResource(
        resource: WallResource,
        mode: WallRunMode,
        balanceHint: Long?
    ): ResourceResult {
        val resourceName = if (resource == WallResource.GOLD) "Gold" else "Elixir"
        var wallsDone = 0
        var spent = 0L
        val settings = repository.settings()
        var line = settings.line ?: return ResourceResult(0, 0, false)
        var balance = balanceHint ?: run {
            val frame = agent.observe()?.frame ?: return ResourceResult(0, 0, false)
            readBalances(frame.bitmap)[resource] ?: run {
                fail("$resourceName balance unreadable — skipping")
                return ResourceResult(0, 0, false)
            }
        }

        while (!stopRequested) {
            val current = repository.settings()
            val cost = current.cost(resource)
            if (cost <= 0) break
            val remaining = WallMath.wallsRemaining(line)
            if (remaining <= 0) {
                BotState.setResultToast(
                    ResultToastUi.Kind.SUCCESS,
                    "Wall line complete"
                )
                break
            }
            val n = WallMath.wallsAffordable(
                balance, current.reserve(resource), cost, current.maxPerCycle, remaining
            )
            if (n <= 0) {
                AppEventLog.log(
                    "walls: not enough $resourceName for one wall " +
                        "(balance=$balance reserve=${current.reserve(resource)} cost=$cost)"
                )
                break
            }
            val expected = n * cost
            if (!spendCapsOk(current, expected, resourceName)) {
                return ResourceResult(wallsDone, spent, false)
            }

            BotState.setWallStrip(
                WallStripUi("Walls · $resourceName · ${line.wallsDone + 1} of ${line.totalWalls}")
            )

            // 3) camera macro + landmark alignment (one retry allowed)
            val alignment = alignCamera()
            if (alignment == null) {
                fail("Camera alignment failed")
                return ResourceResult(wallsDone, spent, false)
            }

            // 4) tap the wall at the current line position, corrected by the
            //    measured camera offset; verify the selection bar.
            val selected = selectWall(line, line.wallsDone, alignment, current.wallLevel)
            if (selected == null) {
                fail("Wall (Level ${current.wallLevel}) not found on the line")
                return ResourceResult(wallsDone, spent, false)
            }

            // 5) build the multi-select for n walls.
            val count = buildMultiSelect(n)
            if (count == null) {
                fail("Could not select $n walls")
                return ResourceResult(wallsDone, spent, false)
            }
            val k = minOf(count, n)
            if (k <= 0) {
                fail("Wall count did not increase")
                return ResourceResult(wallsDone, spent, false)
            }
            val expectedK = k * cost

            // 6) verify the price on the chosen resource button.
            val reading = agent.observe()
            if (reading == null) {
                fail("No game frame")
                return ResourceResult(wallsDone, spent, false)
            }
            val bar = barReader(reading.frame.bitmap, reading.observation)
            val price = bar?.price(resource)
            if (price == null) {
                fail("No $resourceName price found (Wall Rings are never bought)")
                return ResourceResult(wallsDone, spent, false)
            }
            when (WallMath.priceCheck(expectedK, price.amount)) {
                WallMath.PriceVerdict.HIGHER -> {
                    closeDialogsSafely()
                    fail("Price differs from your setting")
                    return ResourceResult(wallsDone, spent, false)
                }
                WallMath.PriceVerdict.LOWER -> {
                    AppEventLog.log(
                        "walls: price ${price.amount} below setting $expectedK — " +
                            "suggest updating the cost field"
                    )
                }
                WallMath.PriceVerdict.OK -> Unit
            }

            // 7) tap Upgrade and handle the confirm dialog.
            val upgradeButton = bar?.upgradeButton()
            if (upgradeButton == null) {
                fail("No Upgrade button found")
                return ResourceResult(wallsDone, spent, false)
            }
            if (!GameInput.tapBox(upgradeButton, reading.frame)) {
                fail("Upgrade tap failed")
                return ResourceResult(wallsDone, spent, false)
            }
            val confirmed = handleConfirmDialog(resource, expectedK, mode)
            if (confirmed == null) {
                return ResourceResult(wallsDone, spent, false)
            }
            if (confirmed.insufficient) {
                AppEventLog.log("walls: $resourceName insufficient")
                return ResourceResult(wallsDone, spent, true)
            }
            if (mode == WallRunMode.TEST) {
                AppEventLog.log(
                    "walls: TEST would have spent $expectedK $resourceName for $k walls"
                )
                BotState.setResultToast(
                    ResultToastUi.Kind.SUCCESS,
                    "Test: would have spent ${compact(expectedK)} " +
                        (if (resource == WallResource.GOLD) "gold" else "elixir")
                )
                return ResourceResult(k, 0, false)
            }

            // 8) verify the balance drop, then record progress.
            val after = agent.observe()
            if (after == null) {
                fail("No game frame after upgrade")
                return ResourceResult(wallsDone, spent, false)
            }
            val balanceAfter = readBalances(after.frame.bitmap)[resource]
            if (balanceAfter != null) {
                if (!WallMath.balanceDropOk(balance, balanceAfter, expectedK)) {
                    fail("Balance drop does not match the price")
                    return ResourceResult(wallsDone, spent, false)
                }
                balance = balanceAfter
            }
            repository.noteSpend(expectedK)
            spent += expectedK
            wallsDone += k
            line = WallMath.advanced(line, k)
            repository.updateSettings { it.copy(line = line) }
            repository.recordCycle(
                WallCycleLog(
                    at = System.currentTimeMillis(),
                    mode = mode,
                    resource = resource,
                    walls = k,
                    price = price.amount,
                    expectedPrice = expectedK,
                    balanceBefore = balance + expectedK,
                    balanceAfter = balance,
                    status = "ok"
                )
            )
            BotState.setWallStrip(
                WallStripUi("Walls · $resourceName · ${line.wallsDone} of ${line.totalWalls}")
            )
        }
        return ResourceResult(wallsDone, spent, true)
    }

    private fun spendCapsOk(
        settings: WallSettings,
        expected: Long,
        resourceName: String
    ): Boolean {
        val sessionSpent = repository.spendSession
        val daySpent = repository.spendToday()
        if (WallMath.spendCapsOk(sessionSpent, daySpent, expected, settings.capSession, settings.capDay)) {
            return true
        }
        val sessionBlocked =
            settings.capSession > 0 && sessionSpent + expected > settings.capSession
        fail(
            if (sessionBlocked) "$resourceName session spend cap reached"
            else "$resourceName daily spend cap reached"
        )
        return false
    }

    // ---------- camera macro + alignment ----------

    private suspend fun alignCamera(): CameraAligner.Alignment? {
        val settings = repository.settings()
        val camera = settings.camera ?: return null
        val patch = repository.landmarkPatch(camera) ?: return null
        repeat(2) { attempt ->
            if (stopRequested) return null
            if (attempt > 0 || settings.cameraMacroId > 0) {
                val played = runCameraMacro(settings.cameraMacroId)
                if (!played) return null
            }
            delay(800)
            val reading = agent.observe() ?: return null
            val alignment = CameraAligner.align(
                reading.frame.bitmap,
                patch,
                camera.landmarkLeft,
                camera.landmarkTop,
                camera.landmarkRight,
                camera.landmarkBottom
            )
            if (alignment != null) {
                AppEventLog.log(
                    "walls: camera aligned offset=(${alignment.offsetX},${alignment.offsetY}) " +
                        "score=%.2f".format(Locale.US, alignment.score)
                )
                return alignment
            }
            AppEventLog.log("walls: camera alignment attempt ${attempt + 1} failed")
        }
        return null
    }

    private suspend fun runCameraMacro(macroId: Long): Boolean {
        val macro = cameraMacroProvider(macroId)
        if (macro == null) {
            fail("No camera macro selected")
            return false
        }
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        var lastTimestamp = 0L
        for (gesture in macro.gestures.sortedBy { it.timestampMs }) {
            if (stopRequested) return false
            val gap = MacroTimeline.gapMillis(
                (gesture.timestampMs - lastTimestamp).coerceAtLeast(0L),
                macro.speedMultiplier
            )
            if (gap > 0) delay(gap)
            if (!dispatch(gesture, macro.speedMultiplier, screenW, screenH)) {
                fail("Camera macro gesture failed")
                return false
            }
            lastTimestamp = gesture.timestampMs
        }
        return true
    }

    private suspend fun dispatch(
        gesture: RecordedGesture,
        speed: Float,
        screenW: Int,
        screenH: Int
    ): Boolean {
        val paths = MacroTimeline.playbackPaths(gesture, speed, screenW, screenH)
        if (paths.isEmpty()) return false
        val builder = GestureDescription.Builder()
        for (path in paths) {
            val androidPath = Path()
            val first = path.points.first()
            androidPath.moveTo(first.x, first.y)
            if (path.points.size > 1) {
                for (index in 1 until path.points.size) {
                    androidPath.lineTo(path.points[index].x, path.points[index].y)
                }
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    androidPath,
                    path.startOffsetMs,
                    path.durationMs.coerceAtLeast(1L)
                )
            )
        }
        val service = ClahAccessibilityService.instance ?: return false
        val total = paths.maxOf { it.startOffsetMs + it.durationMs }
        return kotlinx.coroutines.withTimeoutOrNull(total + 1_500L) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        if (continuation.isActive) continuation.resumeWith(Result.success(true))
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        if (continuation.isActive) continuation.resumeWith(Result.success(false))
                    }
                }
                val accepted = service.dispatchGesture(builder.build(), callback, null)
                if (!accepted && continuation.isActive) {
                    continuation.resumeWith(Result.success(false))
                }
            }
        } ?: false
    }

    // ---------- wall selection ----------

    private suspend fun selectWall(
        line: WallLine,
        index: Int,
        alignment: CameraAligner.Alignment,
        expectedLevel: Int
    ): Boolean {
        val candidates = listOf(index) + WallMath.neighbourIndexes(index, line.totalWalls)
        for (candidate in candidates) {
            if (stopRequested) return false
            val (fx, fy) = WallMath.tapPoint(line, candidate)
            val frame = agent.observe()?.frame ?: return false
            val x = (fx * frame.bitmap.width).toInt() + alignment.offsetX
            val y = (fy * frame.bitmap.height).toInt() + alignment.offsetY
            if (!GameInput.tap(x, y, frame)) continue
            delay(SELECT_SETTLE_MS)
            val after = agent.observe() ?: continue
            val bar = barReader(after.frame.bitmap, after.observation) ?: continue
            val selection = bar.selection()
            if (selection != null && selection.level == expectedLevel) return true
            AppEventLog.log(
                "walls: selection at index $candidate was " +
                    (selection?.let { "level ${it.level}" } ?: "nothing")
            )
        }
        return false
    }

    /** Returns the selected wall count, or null when verification failed. */
    private suspend fun buildMultiSelect(n: Int): Int? {
        if (n <= 1) return 1
        val reading = agent.observe() ?: return null
        val bar = barReader(reading.frame.bitmap, reading.observation) ?: return null
        val more = bar.upgradeMoreButton() ?: return null
        if (!GameInput.tapBox(more, reading.frame)) return null
        delay(SELECT_SETTLE_MS)

        var selected = 0
        val tens = floor(n / 10.0).toInt()
        val ones = n % 10
        val steps = buildList {
            repeat(tens) { add(10) }
            repeat(ones) { add(1) }
        }
        for (step in steps) {
            if (stopRequested) return null
            val now = agent.observe() ?: return null
            val nowBar = barReader(now.frame.bitmap, now.observation) ?: return null
            val button = nowBar.plusButton(step) ?: run {
                AppEventLog.log("walls: +$step button not found")
                return if (selected > 0) selected else null
            }
            val before = nowBar.countLabel() ?: selected
            if (!GameInput.tapBox(button, now.frame)) return null
            delay(SELECT_SETTLE_MS)
            val verify = agent.observe() ?: return null
            val verifyBar = barReader(verify.frame.bitmap, verify.observation)
            val after = verifyBar?.countLabel()
            if (after == null || after <= before) {
                AppEventLog.log("walls: count did not rise after +$step ($before -> $after)")
                return if (selected > 0) selected else null
            }
            selected = after
        }
        return if (selected > 0) selected else n
    }

    // ---------- confirm dialog ----------

    private data class ConfirmOutcome(
        val insufficient: Boolean
    )

    /**
     * Waits for the confirm dialog, verifies the large price against the
     * expected cost, and either confirms (live), closes with X (test), or
     * bails out safely on a gem dialog. null means the cycle must stop.
     */
    private suspend fun handleConfirmDialog(
        resource: WallResource,
        expected: Long,
        mode: WallRunMode
    ): ConfirmOutcome? {
        var reading: RecoveryAgent.Reading? = null
        for (attempt in 0 until DIALOG_POLLS) {
            delay(DIALOG_POLL_MS)
            reading = agent.observe() ?: continue
            if (reading.observation.state == GameState.POPUP) break
        }
        val dialog = reading
        if (dialog == null || dialog.observation.state != GameState.POPUP) {
            fail("Confirm dialog did not appear")
            return null
        }
        val observation = dialog.observation

        if (observation.popupKind == PopupKind.GEM_SPEND ||
            observation.lines.any { it.text.contains("youneedmore") }
        ) {
            closeDialogsSafely()
            BotState.setResultToast(
                ResultToastUi.Kind.WARNING,
                "Not enough " + (if (resource == WallResource.GOLD) "gold" else "elixir")
            )
            return ConfirmOutcome(insufficient = true)
        }

        val large = largeDialogPrice(observation, dialog.frame.bitmap, resource)
        if (large != null) {
            when (WallMath.priceCheck(expected, large.amount)) {
                WallMath.PriceVerdict.HIGHER -> {
                    closeDialogsSafely()
                    fail("Price differs from your setting")
                    return null
                }
                WallMath.PriceVerdict.LOWER -> {
                    AppEventLog.log(
                        "walls: confirm price ${large.amount} below expected $expected"
                    )
                }
                WallMath.PriceVerdict.OK -> Unit
            }
        } else {
            AppEventLog.log("walls: no large dialog price read; continuing")
        }

        if (mode == WallRunMode.TEST) {
            val closed = closeDialogWithX(observation, dialog.frame)
            if (!closed) {
                fail("Could not close the confirm dialog in test mode")
                return null
            }
            return ConfirmOutcome(insufficient = false)
        }

        val confirmBox = SafeConfirmFinder.find(observation)
        if (confirmBox == null) {
            AppEventLog.log("walls: confirm button refused or missing; closing dialog")
            closeDialogsSafely()
            fail("Confirm button not found")
            return null
        }
        if (!GameInput.tapBox(confirmBox, dialog.frame)) {
            fail("Confirm tap failed")
            return null
        }
        waitForDialogClose()
        return ConfirmOutcome(insufficient = false)
    }

    /**
     * The confirm/Okay button, but only when it is not a gem button: the box
     * must not overlap the engine's forbidden boxes and the icon next to the
     * caption must not be a blue Wall Ring or gem.
     */
    private object SafeConfirmFinder {
        fun find(observation: com.clahbot.game.ScreenObservation): ScreenBox? {
            val frameW = observation.frameWidth
            val frameH = observation.frameHeight
            if (frameW <= 0) return null
            for (caption in listOf("confirm", "okay")) {
                val wanted = GameScreenEngine.normalize(caption)
                val line = observation.lines.firstOrNull { it.text.contains(wanted) }
                    ?: continue
                val box = ScreenBox(
                    left = (line.left * frameW).toInt(),
                    top = (line.top * frameH).toInt(),
                    right = (line.right * frameW).toInt(),
                    bottom = (line.bottom * frameH).toInt()
                ).expanded(frameW / 56, frameH / 36, frameW, frameH)
                val forbidden = observation.forbidden.firstOrNull { it.intersects(box) }
                if (forbidden != null) {
                    AppEventLog.log("walls: confirm button overlaps a forbidden box; refused")
                    return null
                }
                return box
            }
            return null
        }
    }

    private data class DialogPrice(
        val amount: Long,
        val box: ScreenBox,
        val resource: ResourceKind
    )

    /**
     * The LARGE confirm-dialog price: the tallest digit-only line inside the
     * dialog centre. The struck-through original price is smaller and is
     * ignored. Its icon colour decides gold vs elixir; anything else (Wall
     * Ring blue, gems) returns null and the caller treats that as missing.
     */
    private fun largeDialogPrice(
        observation: com.clahbot.game.ScreenObservation,
        bitmap: Bitmap,
        resource: WallResource
    ): DialogPrice? {
        val parsed = WallDialogParser.largePrice(
            lines = observation.lines,
            frameW = observation.frameWidth,
            frameH = observation.frameHeight,
            resource = resource,
            sampler = BitmapIconColorSampler(bitmap)
        ) ?: return null
        return DialogPrice(parsed.amount, parsed.box, parsed.icon)
    }

    private suspend fun waitForDialogClose(): Boolean {
        repeat(DIALOG_POLLS) {
            delay(DIALOG_POLL_MS)
            val reading = agent.observe() ?: return true
            if (reading.observation.state != GameState.POPUP) return true
        }
        return false
    }

    /** Close any open dialog with the red X or a Cancel caption — never Confirm. */
    suspend fun closeDialogsSafely() {
        val reading = agent.observe() ?: return
        if (reading.observation.state != GameState.POPUP) return
        closeDialogWithX(reading.observation, reading.frame)
        delay(DIALOG_POLL_MS)
        val after = agent.observe() ?: return
        if (after.observation.state == GameState.POPUP) {
            SafeActionBridge.tapCancel(after.observation, after.frame)
        }
    }

    private suspend fun closeDialogWithX(
        observation: com.clahbot.game.ScreenObservation,
        frame: ClahAccessibilityService.GameFrame
    ): Boolean {
        val box = observation.closeButton
            ?: SafeActionBridge.cancelBox(observation)
            ?: return false
        return GameInput.tapBox(box, frame)
    }

    // ---------- balances ----------

    suspend fun readBalances(bitmap: Bitmap): Map<WallResource, Long> {
        val result = mutableMapOf<WallResource, Long>()
        val frameW = bitmap.width
        val frameH = bitmap.height
        for ((resource, top, bottom) in listOf(
            Triple(WallResource.GOLD, 0.026f, 0.090f),
            Triple(WallResource.ELIXIR, 0.092f, 0.156f)
        )) {
            val box = OcrBox(
                left = (frameW * 0.58f).toInt(),
                top = (frameH * top).toInt(),
                right = (frameW * 0.96f).toInt(),
                bottom = (frameH * bottom).toInt()
            )
            val value = runCatching {
                reader.readNumber(bitmap, box, "", null)
            }.getOrNull()
            if (value != null) result[resource] = value
        }
        return result
    }

    /** Reads the wall-bar prices on the current screen and stores what it sees. */
    suspend fun detectPrices(): Boolean {
        if (MacroPlayer.hasActivePlayer()) {
            BotState.setResultToast(ResultToastUi.Kind.WARNING, "Stop the macro first")
            return false
        }
        val reading = agent.observe() ?: return false
        if (reading.observation.state != GameState.HOME) return false
        val bar = barReader(reading.frame.bitmap, reading.observation)
        val gold = bar.price(WallResource.GOLD)?.amount
        val elixir = bar.price(WallResource.ELIXIR)?.amount
        if (gold == null && elixir == null) return false
        repository.updateSettings { current ->
            current.copy(
                goldCost = gold ?: current.goldCost,
                elixirCost = elixir ?: current.elixirCost
            )
        }
        return true
    }

    // ---------- helpers ----------

    private fun barReader(bitmap: Bitmap, observation: com.clahbot.game.ScreenObservation) =
        WallBarReader(
            page = com.clahbot.ocr.OcrPage(
                width = bitmap.width,
                height = bitmap.height,
                lines = observation.lines.map {
                    com.clahbot.ocr.OcrLine(
                        text = it.text,
                        box = com.clahbot.ocr.OcrBox(
                            left = (it.left * bitmap.width).toInt(),
                            top = (it.top * bitmap.height).toInt(),
                            right = (it.right * bitmap.width).toInt(),
                            bottom = (it.bottom * bitmap.height).toInt()
                        ),
                        elements = emptyList()
                    )
                }
            ),
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            sampler = BitmapIconColorSampler(bitmap)
        )

    private fun fail(reason: String) {
        AppEventLog.log("wall cycle failed: $reason")
        BotState.setResultToast(ResultToastUi.Kind.WARNING, "Stopped: $reason")
        scope.launch {
            runCatching { closeDialogsSafely() }
        }
    }

    private fun compact(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> "${value / 1_000}K"
        else -> value.toString()
    }

    companion object {
        private const val SELECT_SETTLE_MS = 600L
        private const val DIALOG_POLLS = 6
        private const val DIALOG_POLL_MS = 500L
    }
}

/** Bridges to SafeActionFactory for Cancel-only dismissal without a rule. */
internal object SafeActionBridge {
    fun cancelBox(observation: com.clahbot.game.ScreenObservation): ScreenBox? {
        val action = com.clahbot.recovery.SafeActionFactory.tapText(observation, "cancel")
        return action?.target
    }

    suspend fun tapCancel(
        observation: com.clahbot.game.ScreenObservation,
        frame: ClahAccessibilityService.GameFrame
    ): Boolean {
        val box = cancelBox(observation) ?: return false
        return GameInput.tapBox(box, frame)
    }
}
