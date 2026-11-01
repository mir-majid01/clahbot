package com.clahbot.util

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionHelperTest {

    private fun put(enabled: Int, services: String) {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled)
        Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services
        )
    }

    @Test
    fun staleListWithMasterOffIsDisabled() {
        put(
            enabled = 0,
            services = "com.clahbot/com.clahbot.service.ClahAccessibilityService"
        )
        assertEquals(
            PermissionHelper.AccessibilityStatus.DISABLED,
            PermissionHelper.accessibilityStatus(RuntimeEnvironment.getApplication())
        )
    }

    @Test
    fun enabledButNotConnectedIsItsOwnState() {
        put(
            enabled = 1,
            services = "com.clahbot/com.clahbot.service.ClahAccessibilityService"
        )
        assertEquals(
            PermissionHelper.AccessibilityStatus.ENABLED_NOT_CONNECTED,
            PermissionHelper.accessibilityStatus(RuntimeEnvironment.getApplication())
        )
    }

    @Test
    fun anUnrelatedServiceDoesNotCount() {
        put(
            enabled = 1,
            services = "com.example/com.example.Service:com.other/.OtherService"
        )
        assertEquals(
            PermissionHelper.AccessibilityStatus.DISABLED,
            PermissionHelper.accessibilityStatus(RuntimeEnvironment.getApplication())
        )
    }
}
