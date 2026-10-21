package com.clahbot.execution

import com.clahbot.data.SharedVisionBuffer
import com.clahbot.data.models.AutomationMode
import com.clahbot.data.models.AutomationState
import com.clahbot.data.models.BotConfig
import com.clahbot.data.models.GameScreen
import com.clahbot.monitor.WatchdogManager
import com.clahbot.vision.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class StateMachine(
    private val config: BotConfig,
    private val gestureExecutor: GestureExecutor,
    private val ocrEngine: OcrEngine
) {
    private var executionJob: Job? = null
    private var paused = false
    private var collectNowRequested = false

    fun start(scope: CoroutineScope) {
        executionJob?.cancel()
        paused = false
        SharedVisionBuffer.setRunning(true)
        SharedVisionBuffer.resetSession()

        if (!com.clahbot.service.ClahAccessibilityService.isEnabled()) {
            SharedVisionBuffer.setStatus("ERROR: Enable accessibility service in Settings > Accessibility > ClahBOT")
            SharedVisionBuffer.setRunning(false)
            return
        }

        executionJob = scope.launch(Dispatchers.Default) {
            SharedVisionBuffer.setAutomationState(AutomationState.IDLE)
            while (isActive && !paused) {
                if (!WatchdogManager.canContinue()) {
                    SharedVisionBuffer.setAutomationState(AutomationState.PAUSED)
                    SharedVisionBuffer.setStatus("Paused: ${WatchdogManager.pauseReason}")
                    delay(5000)
                    continue
                }

                val snapshot = SharedVisionBuffer.latest()

                if (config.autoPauseOnWarning && snapshot.gameScreen == GameScreen.WARNING) {
                    SharedVisionBuffer.setAutomationState(AutomationState.PAUSED)
                    SharedVisionBuffer.setStatus("Warning detected - auto paused")
                    paused = true
                    break
                }

                when (config.mode) {
                    AutomationMode.SEARCH_ONLY -> runSearchOnly(snapshot)
                    AutomationMode.COLLECT_ONLY -> runCollectOnly(snapshot)
                    AutomationMode.FULL_FARM -> runFullFarm(snapshot)
                    AutomationMode.BUILDER_BASE -> runBuilderBase(snapshot)
                }

                gestureExecutor.delayGaussian(800, 200)
            }
            SharedVisionBuffer.setRunning(false)
        }
    }

    fun pause() {
        paused = true
        SharedVisionBuffer.setAutomationState(AutomationState.PAUSED)
        SharedVisionBuffer.setRunning(false)
    }

    fun resume(scope: CoroutineScope) {
        start(scope)
    }

    fun stop() {
        paused = true
        executionJob?.cancel()
        SharedVisionBuffer.setAutomationState(AutomationState.STOPPED)
        SharedVisionBuffer.setRunning(false)
    }

    fun requestCollectNow() {
        collectNowRequested = true
    }

    private suspend fun runSearchOnly(snapshot: com.clahbot.data.models.VisionSnapshot) {
        when (snapshot.gameScreen) {
            GameScreen.HOME_VILLAGE -> {
                SharedVisionBuffer.setAutomationState(AutomationState.SEARCHING)
                SharedVisionBuffer.setStatus("Navigating to attack search...")
                snapshot.attackButton?.let { tapAndWait(it) }
            }
            GameScreen.ATTACK_SEARCH -> {
                SharedVisionBuffer.setAutomationState(AutomationState.SEARCHING)
                val gold = ocrEngine.parseLootValue(snapshot.lootGold)
                val elixir = ocrEngine.parseLootValue(snapshot.lootElixir)
                SharedVisionBuffer.setStatus("Loot: ${snapshot.lootGold ?: "?"} gold, ${snapshot.lootElixir ?: "?"} elixir")

                if (gold >= config.minGold || elixir >= config.minElixir) {
                    SharedVisionBuffer.setStatus("Target found! Gold=$gold Elixir=$elixir")
                    SharedVisionBuffer.updateStats { it.copy(searchesPerformed = it.searchesPerformed + 1) }
                } else {
                    snapshot.nextButton?.let {
                        SharedVisionBuffer.setStatus("Searching next target...")
                        tapAndWait(it)
                        SharedVisionBuffer.updateStats { it.copy(searchesPerformed = it.searchesPerformed + 1) }
                    } ?: snapshot.searchButton?.let {
                        tapAndWait(it)
                    }
                }
            }
            else -> {
                SharedVisionBuffer.setStatus("Waiting for game screen (${snapshot.gameScreen})...")
            }
        }
    }

    private suspend fun runCollectOnly(snapshot: com.clahbot.data.models.VisionSnapshot) {
        if (collectNowRequested || snapshot.gameScreen == GameScreen.HOME_VILLAGE) {
            SharedVisionBuffer.setAutomationState(AutomationState.COLLECTING)
            SharedVisionBuffer.setStatus("Collecting resources...")
            for (collector in snapshot.collectors) {
                tapAndWait(collector)
                SharedVisionBuffer.updateStats {
                    it.copy(resourcesCollected = it.resourcesCollected + 1000)
                }
            }
            collectNowRequested = false
        }
    }

    private suspend fun runFullFarm(snapshot: com.clahbot.data.models.VisionSnapshot) {
        runCollectOnly(snapshot)

        when (snapshot.gameScreen) {
            GameScreen.HOME_VILLAGE -> {
                snapshot.attackButton?.let { tapAndWait(it) }
            }
            GameScreen.ATTACK_SEARCH -> {
                val gold = ocrEngine.parseLootValue(snapshot.lootGold)
                val elixir = ocrEngine.parseLootValue(snapshot.lootElixir)
                if (gold >= config.minGold || elixir >= config.minElixir) {
                    snapshot.searchButton?.let { tapAndWait(it) }
                    SharedVisionBuffer.setAutomationState(AutomationState.ATTACKING)
                    SharedVisionBuffer.updateStats { it.copy(attacksLaunched = it.attacksLaunched + 1) }
                } else {
                    snapshot.nextButton?.let { tapAndWait(it) }
                }
            }
            GameScreen.ATTACK_DEPLOY -> {
                SharedVisionBuffer.setAutomationState(AutomationState.ATTACKING)
                SharedVisionBuffer.setStatus("Deploying troops...")
                deployTroops(snapshot.screenWidth, snapshot.screenHeight)
            }
            else -> {}
        }
    }

    private suspend fun runBuilderBase(snapshot: com.clahbot.data.models.VisionSnapshot) {
        SharedVisionBuffer.setAutomationState(AutomationState.COLLECTING)
        SharedVisionBuffer.setStatus("Builder base mode - collecting...")
        for (collector in snapshot.collectors) {
            tapAndWait(collector)
        }
    }

    private suspend fun deployTroops(screenW: Int, screenH: Int) {
        val deployY = screenH * 0.92f
        val slots = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        for (slot in slots) {
            val x = screenW * slot
            gestureExecutor.swipeBezier(x, deployY, screenW * 0.5f, screenH * 0.4f, 350)
            gestureExecutor.delayGaussian(400, 100)
        }
    }

    private suspend fun tapAndWait(point: com.clahbot.data.models.TargetPoint) {
        val success = suspendCancellableCoroutine<Boolean> { cont ->
            gestureExecutor.tap(point) { cont.resume(it) }
        }
        if (!success) {
            SharedVisionBuffer.setStatus("Tap failed - check accessibility service")
        }
        gestureExecutor.delayGaussian(600, 150)
    }
}
