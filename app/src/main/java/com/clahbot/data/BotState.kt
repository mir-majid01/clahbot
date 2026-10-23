package com.clahbot.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ControllerState {
    IDLE,
    RECORDING,
    READY,
    PLAYING,
    PAUSED,
    STOPPING,
    ERROR
}

/**
 * Single source of truth for what the controller and the player are doing.
 * Replaces the scattered isBuilding / isPlaying / isRecording flags.
 */
object BotState {

    private val _state = MutableStateFlow(ControllerState.IDLE)
    val state: StateFlow<ControllerState> = _state.asStateFlow()

    private val _status = MutableStateFlow("Ready")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _selectedMacroId = MutableStateFlow(-1L)
    val selectedMacroId: StateFlow<Long> = _selectedMacroId.asStateFlow()

    private val _activeMacroName = MutableStateFlow<String?>(null)
    val activeMacroName: StateFlow<String?> = _activeMacroName.asStateFlow()

    private val _recordedGestureCount = MutableStateFlow(0)
    val recordedGestureCount: StateFlow<Int> = _recordedGestureCount.asStateFlow()

    private val _canSave = MutableStateFlow(false)
    val canSave: StateFlow<Boolean> = _canSave.asStateFlow()

    fun setState(value: ControllerState) {
        _state.value = value
    }

    fun setStatus(value: String) {
        _status.value = value
    }

    fun setSelectedMacroId(id: Long) {
        _selectedMacroId.value = id
    }

    fun setActiveMacro(name: String?) {
        _activeMacroName.value = name
    }

    fun setRecordedCount(count: Int) {
        _recordedGestureCount.value = count
        _canSave.value = count > 0
    }

    fun clearRecording() {
        setRecordedCount(0)
    }

    fun isBusy(): Boolean {
        val current = _state.value
        return current == ControllerState.PLAYING || current == ControllerState.PAUSED
    }
}
