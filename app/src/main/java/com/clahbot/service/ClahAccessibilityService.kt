package com.clahbot.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class ClahAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore our own overlay windows; only remember other apps.
            if (pkg != packageName) foregroundPackage = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: ClahAccessibilityService? = null
            private set

        @Volatile
        private var foregroundPackage: String? = null

        fun isEnabled(): Boolean = instance != null

        /**
         * Reads the focused application window right now. Our own overlay is a
         * TYPE_SYSTEM window, so filtering to TYPE_APPLICATION skips the
         * controller instead of mistaking it for the foreground app.
         */
        fun currentForegroundPackage(): String? {
            val service = instance ?: return null
            val windows = try {
                service.windows
            } catch (_: Exception) {
                null
            } ?: return null
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val window = apps.firstOrNull { it.isActive }
                ?: apps.firstOrNull { it.isFocused }
                ?: apps.firstOrNull()
                ?: return null
            return try {
                window.root?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Unknown is treated as the game: a stale or missing signal must not
         * trap playback in a permanent pause.
         */
        fun isGameForeground(): Boolean {
            val pkg = currentForegroundPackage() ?: foregroundPackage ?: return true
            return pkg.startsWith("com.supercell")
        }

        fun foregroundLabel(): String =
            currentForegroundPackage() ?: foregroundPackage ?: "another app"
    }
}
