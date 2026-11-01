package com.clahbot.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.clahbot.service.ClahAccessibilityService

object PermissionHelper {

    enum class AccessibilityStatus {
        DISABLED,
        ENABLED_NOT_CONNECTED,
        CONNECTED
    }

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
     * Settings can list the service while the accessibility master switch is
     * off (a common stale state on XOS), and a just-enabled service takes a
     * moment to connect. Keep those cases distinct so the UI can explain what
     * is actually wrong instead of asking for a permission that is already on.
     */
    fun accessibilityStatus(context: Context): AccessibilityStatus {
        if (ClahAccessibilityService.isEnabled()) return AccessibilityStatus.CONNECTED
        val resolver = context.contentResolver
        val master = Settings.Secure.getInt(
            resolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        val secureList = Settings.Secure.getString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val listedInSecure = secureList.split(':').any {
            it.equals(ACCESSIBILITY_COMPONENT, ignoreCase = true) ||
                it.equals(ACCESSIBILITY_COMPONENT_SHORT, ignoreCase = true)
        }
        val listedByManager = runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager
            manager?.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )?.any { info ->
                info.resolveInfo?.serviceInfo?.let { service ->
                    service.packageName == context.packageName &&
                        service.name.endsWith("ClahAccessibilityService")
                } == true
            } == true
        }.getOrDefault(false)
        return if (master && (listedInSecure || listedByManager)) {
            AccessibilityStatus.ENABLED_NOT_CONNECTED
        } else {
            AccessibilityStatus.DISABLED
        }
    }

    fun isAccessibilityEnabled(context: Context): Boolean =
        accessibilityStatus(context) != AccessibilityStatus.DISABLED

    fun isAccessibilityConnected(): Boolean = ClahAccessibilityService.isEnabled()

    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

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

    fun showToast(context: Context, message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
