package com.clahbot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class ClahAccessibilityService : AccessibilityService() {

    private var liveRelay: LiveTouchRelay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Never inherit touch exploration from an interrupted live recording.
        clearTouchExploration()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            liveRelay?.onTouchInteractionEnd(event.eventTime)
            return
        }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore our own overlay windows; only remember other apps.
            if (pkg != packageName) foregroundPackage = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        val relay = liveRelay
        liveRelay = null
        relay?.stop()
        if (relay != null) liveRelayLost?.invoke()
        instance = null
        super.onDestroy()
    }

    fun attachLiveRelay(relay: LiveTouchRelay) {
        liveRelay = relay
    }

    fun detachLiveRelay(relay: LiveTouchRelay) {
        if (liveRelay === relay) liveRelay = null
    }

    private fun clearTouchExploration() {
        val info = serviceInfo ?: return
        val touched = (info.flags and
            AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0
        if (!touched) return
        info.flags = info.flags and
            AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        info.eventTypes = info.eventTypes and
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END.inv()
        try {
            setServiceInfo(info)
        } catch (t: Throwable) {
            Log.w("AutoClashLiveRelay", "Could not clear touch exploration", t)
        }
    }

    companion object {
        @Volatile
        var instance: ClahAccessibilityService? = null
            private set

        @Volatile
        var liveRelayLost: (() -> Unit)? = null

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
