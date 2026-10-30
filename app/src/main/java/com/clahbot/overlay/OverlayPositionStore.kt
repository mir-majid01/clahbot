package com.clahbot.overlay

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers dragged overlay positions per orientation. Kept out of
 * SettingsStore because drag updates are frequent and are not user settings.
 */
class OverlayPositionStore private constructor(private val prefs: SharedPreferences) {

    fun get(key: String): OverlayPoint? {
        if (!prefs.contains("$key.x") || !prefs.contains("$key.y")) return null
        return OverlayPoint(prefs.getInt("$key.x", 0), prefs.getInt("$key.y", 0))
    }

    fun set(key: String, point: OverlayPoint) {
        prefs.edit()
            .putInt("$key.x", point.x)
            .putInt("$key.y", point.y)
            .apply()
    }

    fun clear(kindPrefix: String? = null) {
        val edit = prefs.edit()
        for (key in prefs.all.keys) {
            if (kindPrefix == null || key.startsWith("$kindPrefix.")) {
                edit.remove(key)
            }
        }
        edit.apply()
    }

    companion object {
        private const val PREFS = "autoclash_overlay_positions"

        @Volatile
        private var instance: OverlayPositionStore? = null

        fun get(context: Context): OverlayPositionStore =
            instance ?: synchronized(this) {
                instance ?: OverlayPositionStore(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
