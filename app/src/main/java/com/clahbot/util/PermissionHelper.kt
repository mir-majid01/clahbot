package com.clahbot.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.clahbot.service.ClahAccessibilityService

object PermissionHelper {

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    /**
     * True when our exact component is enabled in Settings, even if the service
     * has not connected yet. AccessibilityManager lags behind Settings, which is
     * what produced the "granted but still asked" loop.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        if (ClahAccessibilityService.isEnabled()) return true
        val resolver = context.contentResolver
        val master = Settings.Secure.getInt(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        if (!master) return false
        val list = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return list.split(:).any {
            it.equals(ACCESSIBILITY_COMPONENT, ignoreCase = true) ||
                it.equals(ACCESSIBILITY_COMPONENT_SHORT, ignoreCase = true)
        }
    }

    fun isAccessibilityConnected(): Boolean = ClahAccessibilityService.isEnabled()

    private const val ACCESSIBILITY_COMPONENT =
        "com.clahbot/com.clahbot.service.ClahAccessibilityService"
    private const val ACCESSIBILITY_COMPONENT_SHORT =
        "com.clahbot/.service.ClahAccessibilityService"

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestBatteryOptimizationExemption(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun hasAllPermissions(context: Context): Boolean {
        return canDrawOverlays(context) &&
            isAccessibilityEnabled(context) &&
            isBatteryOptimizationIgnored(context)
    }

    fun hasRequiredPermissions(context: Context): Boolean {
        return canDrawOverlays(context) && isAccessibilityEnabled(context)
    }

    fun showToast(context: Context, message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
