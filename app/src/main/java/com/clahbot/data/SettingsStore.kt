package com.clahbot.data

import android.content.Context
import android.content.SharedPreferences
import com.clahbot.data.models.Macro
import com.clahbot.execution.MacroTimeline
import com.clahbot.overlay.BubbleCorner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }

enum class RecordingMode { PASSIVE, CAPTURE_ONLY }

enum class EffectStyle { RING, DOT, RIPPLE }

/** Per-scope touch-effect configuration (recording and playing). */
data class TouchEffectConfig(
    val enabled: Boolean = true,
    val style: EffectStyle = EffectStyle.RIPPLE,
    val color: Int = 0xFF3D7BFF.toInt(),
    val sizeDp: Int = 48,
    val fadeMs: Int = 600,
    val trail: Boolean = true
)

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
    val bubbleCorner: BubbleCorner = BubbleCorner.TOP_RIGHT,
    val autoDockIcon: Boolean = true,
    val recordingEffect: TouchEffectConfig = TouchEffectConfig(),
    val playingEffect: TouchEffectConfig = TouchEffectConfig(),
    val hapticIntensity: String = "medium",
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
            bubbleCorner = runCatching {
                BubbleCorner.valueOf(
                    prefs.getString(KEY_BUBBLE_CORNER, BubbleCorner.TOP_RIGHT.name)
                        ?: BubbleCorner.TOP_RIGHT.name
                )
            }.getOrDefault(BubbleCorner.TOP_RIGHT),
            autoDockIcon = prefs.getBoolean(KEY_AUTO_DOCK, true),
            recordingEffect = readEffect("rec"),
            playingEffect = readEffect("play"),
            hapticIntensity = prefs.getString(KEY_HAPTIC_INTENSITY, "medium") ?: "medium",
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
            .putString(KEY_BUBBLE_CORNER, normalized.bubbleCorner.name)
            .putBoolean(KEY_AUTO_DOCK, normalized.autoDockIcon)
            .putString(KEY_HAPTIC_INTENSITY, normalized.hapticIntensity)
            .putBoolean(KEY_SAVE_RESULTS, normalized.saveResultsScreenshots)
            .putString(KEY_THEME, normalized.theme.name)
            .apply()
        writeEffect("rec", normalized.recordingEffect)
        writeEffect("play", normalized.playingEffect)
        _settings.value = normalized
    }

    private fun readEffect(prefix: String): TouchEffectConfig = TouchEffectConfig(
        enabled = prefs.getBoolean(KEY_EFFECT_ON + prefix, true),
        style = runCatching { EffectStyle.valueOf(prefs.getString(KEY_EFFECT_STYLE + prefix, EffectStyle.RIPPLE.name) ?: EffectStyle.RIPPLE.name) }.getOrDefault(EffectStyle.RIPPLE),
        color = prefs.getInt(KEY_EFFECT_COLOR + prefix, 0xFF3D7BFF.toInt()),
        sizeDp = prefs.getInt(KEY_EFFECT_SIZE + prefix, 48).coerceIn(16, 96),
        fadeMs = prefs.getInt(KEY_EFFECT_FADE + prefix, 600).coerceIn(200, 1500),
        trail = prefs.getBoolean(KEY_EFFECT_TRAIL + prefix, true)
    )

    private fun writeEffect(prefix: String, config: TouchEffectConfig) {
        prefs.edit()
            .putBoolean(KEY_EFFECT_ON + prefix, config.enabled)
            .putString(KEY_EFFECT_STYLE + prefix, config.style.name)
            .putInt(KEY_EFFECT_COLOR + prefix, config.color)
            .putInt(KEY_EFFECT_SIZE + prefix, config.sizeDp.coerceIn(16, 96))
            .putInt(KEY_EFFECT_FADE + prefix, config.fadeMs.coerceIn(200, 1500))
            .putBoolean(KEY_EFFECT_TRAIL + prefix, config.trail)
            .apply()
    }

    private fun normalizeRepeat(value: String?): String {
        if (value == Macro.REPEAT_INFINITE) return Macro.REPEAT_INFINITE
        val count = value?.toIntOrNull()
        return if (count != null && count in 1..Macro.MAX_CUSTOM_REPEAT) {
            count.toString()
        } else {
            Macro.REPEAT_ONCE
        }
    }

    companion object {
        const val MIN_ALPHA = 0.5f
        const val MIN_CONTROLLER_SCALE = 0.8f
        const val MAX_CONTROLLER_SCALE = 1.3f
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
        private const val KEY_BUBBLE_CORNER = "bubble_corner"
        private const val KEY_AUTO_DOCK = "auto_dock_icon"
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_EFFECT_ON = "touch_effect_on_"
        private const val KEY_EFFECT_STYLE = "touch_effect_style_"
        private const val KEY_EFFECT_COLOR = "touch_effect_color_"
        private const val KEY_EFFECT_SIZE = "touch_effect_size_"
        private const val KEY_EFFECT_FADE = "touch_effect_fade_"
        private const val KEY_EFFECT_TRAIL = "touch_effect_trail_"
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
