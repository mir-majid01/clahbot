package com.clahbot.data

import com.clahbot.data.models.Macro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ControllerState {
    INACTIVE,
    READY,
    RECORDING,
    RECORDING_PAUSED,
    REVIEW,
    PLAYING,
    PLAYBACK_PAUSED,
    STOPPING,
    ERROR;

    val isRecording: Boolean
        get() = this == RECORDING || this == RECORDING_PAUSED

    val isPlayback: Boolean
        get() = this == PLAYING || this == PLAYBACK_PAUSED

    val isBusy: Boolean
        get() = isRecording || isPlayback
}

/**
 * Single source of truth for what the controller and the player are doing.
 * The dashboard, the floating controller and the player all read this.
 */
object BotState {

    private val _state = MutableStateFlow(ControllerState.INACTIVE)
    val state: StateFlow<ControllerState> = _state.asStateFlow()

    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _selectedMacroId = MutableStateFlow(-1L)
    val selectedMacroId: StateFlow<Long> = _selectedMacroId.asStateFlow()

    private val _activeMacroId = MutableStateFlow(-1L)
    val activeMacroId: StateFlow<Long> = _activeMacroId.asStateFlow()

    private val _activeMacroName = MutableStateFlow<String?>(null)
    val activeMacroName: StateFlow<String?> = _activeMacroName.asStateFlow()

    private val _interrupted = MutableStateFlow(false)
    val interrupted: StateFlow<Boolean> = _interrupted.asStateFlow()

    private val _replayLatencyMs = MutableStateFlow(0L)
    val replayLatencyMs: StateFlow<Long> = _replayLatencyMs.asStateFlow()

    private val _recordedGestureCount = MutableStateFlow(0)
    val recordedGestureCount: StateFlow<Int> = _recordedGestureCount.asStateFlow()

    fun setState(value: ControllerState) {
        _state.value = value
    }

    fun setStatus(value: String) {
        _status.value = value
    }

    fun setSelectedMacroId(id: Long) {
        _selectedMacroId.value = id
    }

    fun setActiveMacro(id: Long, name: String?) {
        _activeMacroId.value = id
        _activeMacroName.value = name
    }

    fun clearActiveMacro() {
        _activeMacroId.value = -1L
        _activeMacroName.value = null
    }

    fun setInterrupted(value: Boolean) {
        _interrupted.value = value
    }

    fun setReplayLatency(ms: Long) {
        _replayLatencyMs.value = ms.coerceAtLeast(0L)
    }

    fun setRecordedCount(count: Int) {
        _recordedGestureCount.value = count
    }

    fun clearRecording() {
        setRecordedCount(0)
    }
}

/**
 * Keeps a selection valid after the library changes. Returns -1 when the
 * selected macro no longer exists so stale ids cannot be played.
 */
object MacroSelection {
    fun reconcile(selectedId: Long, macros: List<Macro>): Long {
        if (selectedId <= 0L) return -1L
        return if (macros.any { it.id == selectedId }) selectedId else -1L
    }
}
