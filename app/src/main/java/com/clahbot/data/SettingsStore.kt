package com.clahbot.data

import android.content.Context
import android.content.SharedPreferences
import com.clahbot.data.models.Macro
import com.clahbot.execution.MacroTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }

enum class RecordingMode { PASSIVE, CAPTURE_ONLY }

data class AppSettings(
    val defaultSpeed: Float = 1f,
    val defaultRepeat: String = Macro.REPEAT_ONCE,
    val recordingMode: RecordingMode = RecordingMode.PASSIVE,
    val hapticFeedback: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val startMinimized: Boolean = false,
    val controllerAlpha: Float = 1f,
    val controllerScale: Float = 1f,
    val bubbleScale: Float = 1f,
    val saveResultsScreenshots: Boolean = false,
    val theme: ThemeMode = ThemeMode.DARK
)

/**
 * Tiny preference store shared by the dashboard, the settings screen and the
 * floating service, so a change in Settings is visible to the controller
 * immediately without a restart.
 */
class SettingsStore private constructor(private val prefs: SharedPreferences) {

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun read(): AppSettings {
        val themeName = prefs.getString(KEY_THEME, ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        val recordingName = prefs.getString(KEY_RECORDING_MODE, null)
        return AppSettings(
            defaultSpeed = MacroTimeline.clampSpeed(prefs.getFloat(KEY_SPEED, 1f)),
            defaultRepeat = normalizeRepeat(prefs.getString(KEY_REPEAT, Macro.REPEAT_ONCE)),
            // "LIVE_RELAY" was the old overlay relay; passive is its replacement.
            recordingMode = if (recordingName == RecordingMode.CAPTURE_ONLY.name) {
                RecordingMode.CAPTURE_ONLY
            } else {
                RecordingMode.PASSIVE
            },
            hapticFeedback = prefs.getBoolean(KEY_HAPTIC, true),
            keepScreenAwake = prefs.getBoolean(KEY_AWAKE, true),
            startMinimized = prefs.getBoolean(KEY_MINIMIZED, false),
            controllerAlpha = prefs.getFloat(KEY_ALPHA, 1f).coerceIn(MIN_ALPHA, 1f),
            controllerScale = prefs.getFloat(KEY_CONTROLLER_SCALE, 1f)
                .coerceIn(MIN_CONTROLLER_SCALE, MAX_CONTROLLER_SCALE),
            bubbleScale = prefs.getFloat(KEY_BUBBLE_SCALE, 1f)
                .coerceIn(MIN_BUBBLE_SCALE, MAX_BUBBLE_SCALE),
            saveResultsScreenshots = prefs.getBoolean(KEY_SAVE_RESULTS, false),
            theme = runCatching { ThemeMode.valueOf(themeName) }.getOrDefault(ThemeMode.DARK)
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        val normalized = next.copy(
            defaultSpeed = MacroTimeline.clampSpeed(next.defaultSpeed),
            defaultRepeat = normalizeRepeat(next.defaultRepeat),
            controllerAlpha = next.controllerAlpha.coerceIn(MIN_ALPHA, 1f),
            controllerScale = next.controllerScale.coerceIn(
                MIN_CONTROLLER_SCALE,
                MAX_CONTROLLER_SCALE
            ),
            bubbleScale = next.bubbleScale.coerceIn(MIN_BUBBLE_SCALE, MAX_BUBBLE_SCALE)
        )
        prefs.edit()
            .putFloat(KEY_SPEED, normalized.defaultSpeed)
            .putString(KEY_REPEAT, normalized.defaultRepeat)
            .putString(KEY_RECORDING_MODE, normalized.recordingMode.name)
            .putBoolean(KEY_HAPTIC, normalized.hapticFeedback)
            .putBoolean(KEY_AWAKE, normalized.keepScreenAwake)
            .putBoolean(KEY_MINIMIZED, normalized.startMinimized)
            .putFloat(KEY_ALPHA, normalized.controllerAlpha)
            .putFloat(KEY_CONTROLLER_SCALE, normalized.controllerScale)
            .putFloat(KEY_BUBBLE_SCALE, normalized.bubbleScale)
            .putBoolean(KEY_SAVE_RESULTS, normalized.saveResultsScreenshots)
            .putString(KEY_THEME, normalized.theme.name)
            .apply()
        _settings.value = normalized
    }

    private fun normalizeRepeat(value: String?): String = when {
        value == Macro.REPEAT_INFINITE -> Macro.REPEAT_INFINITE
        value != null && value.toIntOrNull() != null -> value
        else -> Macro.REPEAT_ONCE
    }

    companion object {
        const val MIN_ALPHA = 0.5f
        const val MIN_CONTROLLER_SCALE = 0.7f
        const val MAX_CONTROLLER_SCALE = 1.15f
        const val MIN_BUBBLE_SCALE = 0.7f
        const val MAX_BUBBLE_SCALE = 1.5f

        private const val PREFS = "autoclash_settings"
        private const val KEY_SPEED = "default_speed"
        private const val KEY_REPEAT = "default_repeat"
        private const val KEY_RECORDING_MODE = "recording_mode"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_AWAKE = "keep_awake"
        private const val KEY_MINIMIZED = "start_minimized"
        private const val KEY_ALPHA = "controller_alpha"
        private const val KEY_CONTROLLER_SCALE = "controller_scale"
        private const val KEY_BUBBLE_SCALE = "bubble_scale"
        private const val KEY_SAVE_RESULTS = "save_results_screenshots"
        private const val KEY_THEME = "theme"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore {
            return instance ?: synchronized(this) {
                instance ?: SettingsStore(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }
}
