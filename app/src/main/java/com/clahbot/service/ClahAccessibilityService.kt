package com.clahbot.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

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

        /** Unknown (no events yet) counts as the game so we never pause falsely. */
        fun isGameForeground(): Boolean {
            val pkg = foregroundPackage ?: return true
            return pkg.startsWith("com.supercell")
        }
    }
}
