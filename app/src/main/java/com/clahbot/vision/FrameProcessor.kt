package com.clahbot.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.clahbot.data.SharedVisionBuffer
import com.clahbot.service.FrameCaptureHolder
import com.clahbot.data.models.GameScreen
import com.clahbot.data.models.TargetPoint
import com.clahbot.data.models.VisionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.util.Log
import kotlin.random.Random

class FrameProcessor(
    private val ocrEngine: OcrEngine,
    private val templateMatcher: TemplateMatcher,
    private val redLineDetector: RedLineDetector
) {
    private var processingJob: Job? = null
    private var targetFps = 2

    companion object {
        private const val TAG = "ClahBOT.Vision"
    }

    fun start(scope: CoroutineScope) {
        processingJob?.cancel()
        processingJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val frame = FrameCaptureHolder.swap()
                if (frame != null && !frame.isRecycled) {
                    val safe = frame.copy(Bitmap.Config.ARGB_8888, false)
                    if (safe != null) {
                        processFrame(safe)
                        safe.recycle()
                    }
                } else {
                    frame?.recycle()
                }
                delay(1000L / targetFps.coerceAtLeast(1))
            }
        }
    }

    fun stop() {
        processingJob?.cancel()
        processingJob = null
    }

    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(1, 10)
    }

    private suspend fun processFrame(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height

        val gameScreen = detectGameScreen(bitmap, w, h)
        val collectors = if (gameScreen == GameScreen.HOME_VILLAGE) {
            detectCollectors(bitmap, w, h)
        } else emptyList()

        val attackBtn = if (gameScreen == GameScreen.HOME_VILLAGE) {
            detectAttackButton(bitmap, w, h)
        } else null

        Log.d(TAG, "Screen: $gameScreen, collectors: ${collectors.size}, attackBtn: ${attackBtn != null}")

        val searchBtn = if (gameScreen == GameScreen.ATTACK_SEARCH) {
            detectSearchButton(bitmap, w, h)
        } else null

        val nextBtn = if (gameScreen == GameScreen.ATTACK_SEARCH) {
            detectNextButton(bitmap, w, h)
        } else null

        val loot = if (gameScreen == GameScreen.ATTACK_SEARCH) {
            ocrEngine.readLootRegion(bitmap, w, h)
        } else null

        val hasRedLine = if (gameScreen == GameScreen.ATTACK_DEPLOY) {
            redLineDetector.detect(bitmap)
        } else false

        val warningText = if (gameScreen == GameScreen.WARNING) {
            ocrEngine.readFullScreen(bitmap)
        } else null

        val snapshot = VisionSnapshot(
            collectors = collectors,
            attackButton = attackBtn,
            searchButton = searchBtn,
            nextButton = nextBtn,
            lootGold = loot?.first,
            lootElixir = loot?.second,
            lootDark = loot?.third,
            gameScreen = gameScreen,
            hasRedLine = hasRedLine,
            warningText = warningText,
            screenWidth = w,
            screenHeight = h
        )

        SharedVisionBuffer.update(snapshot)
    }

    private fun detectGameScreen(bitmap: Bitmap, w: Int, h: Int): GameScreen {
        if (isWarningScreen(bitmap, w, h)) return GameScreen.WARNING

        val leftBottom = sampleColor(bitmap, (w * 0.10).toInt(), (h * 0.92).toInt())
        val rightBottom = sampleColor(bitmap, (w * 0.90).toInt(), (h * 0.92).toInt())
        val topCenter = sampleColor(bitmap, (w * 0.50).toInt(), (h * 0.03).toInt())
        val center = sampleColor(bitmap, (w * 0.50).toInt(), (h * 0.50).toInt())

        val leftBottomBright = colorBrightness(leftBottom)
        val rightBottomBright = colorBrightness(rightBottom)

        if (leftBottomBright > 130 && rightBottomBright > 100) {
            return GameScreen.HOME_VILLAGE
        }

        val topBright = colorBrightness(topCenter)
        if (topBright > 80) {
            val centerBright = colorBrightness(center)
            if (centerBright > 100) {
                return GameScreen.ATTACK_SEARCH
            }
        }

        val bottomRow = averageBrightness(bitmap, 0, (h * 0.88).toInt(), w, h)
        if (bottomRow > 130) {
            return GameScreen.ATTACK_DEPLOY
        }

        val topBar = averageBrightness(bitmap, 0, 0, w, (h * 0.08).toInt())
        if (topBar > 100) {
            return GameScreen.ATTACK_SEARCH
        }

        return GameScreen.UNKNOWN
    }

    private fun sampleColor(bitmap: Bitmap, x: Int, y: Int): Int {
        val sx = x.coerceIn(0, bitmap.width - 1)
        val sy = y.coerceIn(0, bitmap.height - 1)
        return bitmap.getPixel(sx, sy)
    }

    private fun colorBrightness(color: Int): Int {
        return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
    }

    private fun isWarningScreen(bitmap: Bitmap, w: Int, h: Int): Boolean {
        val centerColor = bitmap.getPixel(w / 2, h / 2)
        val r = Color.red(centerColor)
        val g = Color.green(centerColor)
        return r > 180 && g < 80
    }

    private fun averageBrightness(bitmap: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        var sum = 0L
        var count = 0
        val stepX = ((x2 - x1) / 10).coerceAtLeast(1)
        val stepY = ((y2 - y1) / 10).coerceAtLeast(1)
        for (x in x1 until x2 step stepX) {
            for (y in y1 until y2 step stepY) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val c = bitmap.getPixel(x, y)
                    sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                    count++
                }
            }
        }
        return if (count > 0) (sum / count).toInt() else 0
    }

    private fun detectCollectors(bitmap: Bitmap, w: Int, h: Int): List<TargetPoint> {
        val results = mutableListOf<TargetPoint>()
        val step = 12
        val xStart = (w * 0.05).toInt()
        val xEnd = (w * 0.95).toInt()
        val yStart = (h * 0.15).toInt()
        val yEnd = (h * 0.88).toInt()

        for (x in xStart until xEnd step step) {
            for (y in yStart until yEnd step step) {
                val bestColor = bestColorInRadius(bitmap, x, y, 8)
                val r = Color.red(bestColor)
                val g = Color.green(bestColor)
                val b = Color.blue(bestColor)

                val isGold = r > 180 && g > 150 && b < 120 && r > b
                val isElixir = r > 150 && g < 130 && b > 150 && b > g

                if (isGold || isElixir) {
                    val label = if (isGold) "gold" else "elixir"
                    val exists = results.any { kotlin.math.abs(it.x - x) < 40 && kotlin.math.abs(it.y - y) < 40 }
                    if (!exists) {
                        results.add(TargetPoint(x, y, label, 0.7f))
                    }
                }
            }
        }
        SharedVisionBuffer.setStatus("Found ${results.size} collectors (${results.count { it.label == "gold" }} gold, ${results.count { it.label == "elixir" }} elixir)")
        return results
    }

    private fun bestColorInRadius(bitmap: Bitmap, cx: Int, cy: Int, radius: Int): Int {
        var bestR = 0
        var bestG = 0
        var bestB = 0
        var bestSat = 0f
        for (dx in -radius..radius step 3) {
            for (dy in -radius..radius step 3) {
                val px = (cx + dx).coerceIn(0, bitmap.width - 1)
                val py = (cy + dy).coerceIn(0, bitmap.height - 1)
                val c = bitmap.getPixel(px, py)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val max = maxOf(r, g, b).toFloat()
                val min = minOf(r, g, b).toFloat()
                val sat = if (max > 0) (max - min) / max else 0f
                if (sat > bestSat) {
                    bestSat = sat
                    bestR = r
                    bestG = g
                    bestB = b
                }
            }
        }
        return Color.rgb(bestR, bestG, bestB)
    }

    private fun detectAttackButton(bitmap: Bitmap, w: Int, h: Int): TargetPoint? {
        val cx = (w * 0.10).toInt()
        val cy = (h * 0.92).toInt()
        val c = bitmap.getPixel(cx, cy)
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val isOrange = r > 170 && g > 100 && b < 100
        return if (isOrange) TargetPoint(cx, cy, "attack", 0.8f) else null
    }

    private fun detectSearchButton(bitmap: Bitmap, w: Int, h: Int): TargetPoint? {
        val cx = (w * 0.5).toInt()
        val cy = (h * 0.88).toInt()
        val brightness = averageBrightness(bitmap, cx - 60, cy - 20, cx + 60, cy + 20)
        return if (brightness > 130) TargetPoint(cx, cy, "search", 0.75f) else null
    }

    private fun detectNextButton(bitmap: Bitmap, w: Int, h: Int): TargetPoint? {
        val cx = (w * 0.85).toInt()
        val cy = (h * 0.12).toInt()
        val brightness = averageBrightness(bitmap, cx - 40, cy - 20, cx + 40, cy + 20)
        return if (brightness > 120) TargetPoint(cx, cy, "next", 0.75f) else null
    }
}
