package com.clahbot.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class ClahAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: ClahAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
