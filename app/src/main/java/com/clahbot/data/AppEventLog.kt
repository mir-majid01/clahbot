package com.clahbot.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small rolling diagnostic log in the app's private files directory. It is
 * deliberately free of screenshots, loot values and macro names; pull it with
 * `adb exec-out run-as com.clahbot cat files/autoclash_events.log`.
 */
object AppEventLog {

    private const val TAG = "AutoClashEventLog"
    private const val FILE_NAME = "autoclash_events.log"
    private const val MAX_BYTES = 256L * 1024L
    private const val FORMAT = "MM-dd HH:mm:ss.SSS"

    private val lock = Any()

    @Volatile
    private var file: File? = null

    fun init(context: Context, version: String) {
        file = File(context.filesDir, FILE_NAME)
        log("app start version=$version")
    }

    fun log(message: String) {
        val target = file ?: return
        synchronized(lock) {
            try {
                if (target.length() > MAX_BYTES) rotate(target)
                val line = SimpleDateFormat(FORMAT, Locale.US).format(Date()) + " " + message
                target.appendText(line + "\n")
            } catch (throwable: Throwable) {
                Log.w(TAG, "Could not write event log", throwable)
            }
        }
    }

    private fun rotate(target: File) {
        val backup = File(target.parentFile, "$FILE_NAME.1")
        if (backup.exists()) backup.delete()
        if (!target.renameTo(backup)) {
            target.delete()
        }
    }

    fun path(): String? = file?.absolutePath
}
