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

    private val _recordedGestureCount = MutableStateFlow(0)
    val recordedGestureCount: StateFlow<Int> = _recordedGestureCount.asStateFlow()

    private val _repeatCountdown = MutableStateFlow<Int?>(null)
    val repeatCountdown: StateFlow<Int?> = _repeatCountdown.asStateFlow()

    private val _startCountdown = MutableStateFlow<Int?>(null)
    val startCountdown: StateFlow<Int?> = _startCountdown.asStateFlow()

    private val _runAttackCount = MutableStateFlow(0)
    val runAttackCount: StateFlow<Int> = _runAttackCount.asStateFlow()

    private val _playbackRunLabel = MutableStateFlow<String?>(null)
    val playbackRunLabel: StateFlow<String?> = _playbackRunLabel.asStateFlow()

    private val _playbackElapsedMs = MutableStateFlow(0L)
    val playbackElapsedMs: StateFlow<Long> = _playbackElapsedMs.asStateFlow()

    /** Amber smart-recovery strip: label like "Closing a popup… try 1 of 3". */
    private val _recoveryStrip = MutableStateFlow<RecoveryStripUi?>(null)
    val recoveryStrip: StateFlow<RecoveryStripUi?> = _recoveryStrip.asStateFlow()

    /** Auto-dismissing result toast (5 s) for wall/recovery outcomes. */
    private val _resultToast = MutableStateFlow<ResultToastUi?>(null)
    val resultToast: StateFlow<ResultToastUi?> = _resultToast.asStateFlow()

    fun setRecoveryStrip(value: RecoveryStripUi?) {
        _recoveryStrip.value = value
    }

    fun setResultToast(kind: ResultToastUi.Kind, text: String) {
        _resultToast.value = ResultToastUi(id = System.nanoTime(), kind = kind, text = text)
    }

    fun clearResultToast(id: Long) {
        if (_resultToast.value?.id == id) _resultToast.value = null
    }

    fun setState(value: ControllerState) {
        val old = _state.value
        _state.value = value
        if (old != value) {
            android.util.Log.i("AutoClashState", "state $old -> $value (${Thread.currentThread().name})")
        }
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

    fun setRecordedCount(count: Int) {
        _recordedGestureCount.value = count
    }

    fun setRepeatCountdown(seconds: Int?) {
        _repeatCountdown.value = seconds
    }

    fun setStartCountdown(seconds: Int?) {
        _startCountdown.value = seconds
    }

    fun clearRecording() {
        setRecordedCount(0)
    }

    fun beginRunAttacks() {
        _runAttackCount.value = 0
    }

    fun noteRunAttack() {
        _runAttackCount.value = _runAttackCount.value + 1
    }

    fun setPlaybackRunLabel(value: String?) {
        _playbackRunLabel.value = value
    }

    fun setPlaybackElapsed(ms: Long) {
        _playbackElapsedMs.value = ms.coerceAtLeast(0L)
    }
}

data class RecoveryStripUi(val label: String, val tryIndex: Int, val tryCount: Int)

data class ResultToastUi(
    val id: Long,
    val kind: Kind,
    val text: String
) {
    enum class Kind { SUCCESS, WARNING }
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
