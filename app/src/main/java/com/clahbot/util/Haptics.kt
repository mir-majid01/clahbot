package com.clahbot.util

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptics for the overlay. UI taps use the view's own touch feedback; events
 * use a real Vibrator effect so they still work when the overlay window is not
 * focusable. Intensity is a one-shot amplitude.
 */
object Haptics {

    enum class Intensity(val label: String) { LIGHT("light"), MEDIUM("medium"), STRONG("strong");

        companion object {
            fun from(value: String?): Intensity =
                entries.firstOrNull { it.label == value } ?: MEDIUM
        }
    }

    fun tap(view: View?) {
        try {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) {
            // Touch feedback can be disabled by the system.
        }
    }

    fun event(context: Context, intensity: String, durationMs: Long = 30L) {
        vibrate(context, Intensity.from(intensity), durationMs)
    }

    fun test(context: Context, intensity: String) {
        vibrate(context, Intensity.from(intensity), 45L)
    }

    private fun vibrate(context: Context, intensity: Intensity, durationMs: Long) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            val amplitude = when (intensity) {
                Intensity.LIGHT -> 70
                Intensity.MEDIUM -> 160
                Intensity.STRONG -> 255
            }
            val effect = VibrationEffect.createOneShot(
                durationMs.coerceIn(10L, 200L),
                amplitude
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .build()
                )
            }
        } catch (_: Throwable) {
            // Missing permission or vibration disabled by the user.
        }
    }
}
