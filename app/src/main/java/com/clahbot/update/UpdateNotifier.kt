package com.clahbot.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clahbot.R
import com.clahbot.ui.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks for a new release at most every six hours and posts one notification
 * per version. The check uses the unthrottled Releases Atom feed first, so a
 * few manual checks can never exhaust the anonymous GitHub API quota.
 */
object UpdateNotifier {

    private const val PREFS = "autoclash_update_notice"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LAST_NOTIFIED = "last_notified"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 4242

    suspend fun checkAndNotify(context: Context, force: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        val result = withContext(Dispatchers.IO) { GitHubUpdater.check() }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        val release = (result as? GitHubUpdater.CheckResult.Available)?.release ?: return
        if (release.versionCode <= prefs.getInt(KEY_LAST_NOTIFIED, 0)) return
        if (!canNotify(context)) return
        post(context, release)
        prefs.edit().putInt(KEY_LAST_NOTIFIED, release.versionCode).apply()
    }

    private fun canNotify(context: Context): Boolean = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(context: Context, release: UpdateRelease) {
        ensureChannel(context)
        val intent = Intent(context, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_OPEN_UPDATER, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val version = release.title.removePrefix("AutoClash").trim()
            .ifBlank { release.tag }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AutoClash $version is available")
            .setContentText("Tap to download and install the update.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "AutoClash $version is available. Tap to download and install the update."
            ))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "One notification when a new AutoClash release is available"
        }
        manager.createNotificationChannel(channel)
    }
}
