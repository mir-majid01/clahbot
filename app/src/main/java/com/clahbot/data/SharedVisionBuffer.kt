package com.clahbot.data

import com.clahbot.data.models.AutomationState
import com.clahbot.data.models.SessionStats
import com.clahbot.data.models.VisionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

object SharedVisionBuffer {

    private val snapshotRef = AtomicReference(VisionSnapshot())
    private val _automationState = MutableStateFlow(AutomationState.IDLE)
    val automationState: StateFlow<AutomationState> = _automationState.asStateFlow()

    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    fun update(snapshot: VisionSnapshot) {
        snapshotRef.set(snapshot)
    }

    fun latest(): VisionSnapshot = snapshotRef.get()

    fun setAutomationState(state: AutomationState) {
        _automationState.value = state
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun setStatus(message: String) {
        _statusMessage.value = message
    }

    fun updateStats(transform: (SessionStats) -> SessionStats) {
        _sessionStats.value = transform(_sessionStats.value)
    }

    fun resetSession() {
        _sessionStats.value = SessionStats()
    }
}
