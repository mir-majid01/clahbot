package com.clahbot.input

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku
import java.io.File

object InputAccessManager {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_REQUEST_CODE = 0x5348

    data class AccessStatus(
        val ready: Boolean,
        val rootPossible: Boolean,
        val shizukuInstalled: Boolean,
        val shizukuRunning: Boolean,
        val shizukuGranted: Boolean
    )

    fun status(context: Context): AccessStatus {
        val installed = isShizukuInstalled(context)
        val running = isShizukuRunning()
        val granted = running && hasShizukuPermission()
        val root = rootPossible()
        return AccessStatus(
            ready = granted || root,
            rootPossible = root,
            shizukuInstalled = installed,
            shizukuRunning = running,
            shizukuGranted = granted
        )
    }

    fun title(status: AccessStatus): String = when {
        status.shizukuGranted -> "Shizuku access granted"
        status.rootPossible -> "Root access available"
        status.shizukuRunning -> "Shizuku needs permission"
        status.shizukuInstalled -> "Shizuku is not running"
        else -> "Recording access required"
    }

    fun detail(status: AccessStatus): String = when {
        status.shizukuGranted ->
            "AutoClash reads raw touchscreen events as the shell user. No overlay sits in the touch path."

        status.rootPossible ->
            "AutoClash will try su when recording starts. Grant the root prompt if it appears."

        status.shizukuRunning ->
            "Shizuku is running. Grant AutoClash access so it can read the touchscreen without root."

        status.shizukuInstalled ->
            "Start Shizuku, then return here and grant access. Shizuku can be started with ADB or wireless debugging."

        else ->
            "Passive recording needs Shizuku or root to read the touchscreen. " +
                "Without either, recording stays unavailable and the app will not invent a workaround."
    }

    fun unavailableMessage(context: Context): String {
        val status = status(context)
        return when {
            status.shizukuRunning ->
                "Grant Shizuku access in Settings, then try recording again."

            status.shizukuInstalled ->
                "Start Shizuku and grant AutoClash access in Settings, then try again."

            else ->
                "Install Shizuku or root this phone. Passive recording cannot read touches without one."
        }
    }

    /**
     * Must run off the main thread: the root probe may start su and wait for it.
     */
    fun createSource(context: Context): InputEventSource? {
        if (rootPossible()) {
            val root = RootInputSource()
            if (root.probe()) return root
        }
        if (hasShizukuPermission()) {
            return ShizukuInputSource(context)
        }
        return null
    }

    fun requestShizukuPermission(): Boolean {
        return try {
            if (!isShizukuRunning() || Shizuku.isPreV11()) return false
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuRunning() || Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun rootPossible(): Boolean {
        return ROOT_PATHS.any { File(it).exists() }
    }

    fun openShizuku(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (intent != null) {
            try {
                context.startActivity(intent)
            } catch (_: Throwable) {
                // The app is installed but has no launcher.
            }
        }
    }

    fun openShizukuInstall(context: Context) {
        val market = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$SHIZUKU_PACKAGE")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: Throwable) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PACKAGE")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(web)
            } catch (_: Throwable) {
                // No browser or store.
            }
        }
    }

    private val ROOT_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su"
    )
}
