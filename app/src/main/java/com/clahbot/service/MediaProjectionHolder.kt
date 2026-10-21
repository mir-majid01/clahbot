package com.clahbot.service

import android.app.Activity
import android.content.Intent

object MediaProjectionHolder {
    @Volatile
    var resultCode: Int = Activity.RESULT_CANCELED
        private set

    @Volatile
    var resultData: Intent? = null
        private set

    fun set(code: Int, data: Intent) {
        resultCode = code
        resultData = data
    }

    fun take(): Pair<Int, Intent>? {
        val data = resultData ?: return null
        val code = resultCode
        clear()
        return code to data
    }

    fun clear() {
        resultCode = Activity.RESULT_CANCELED
        resultData = null
    }

    fun hasValidToken(): Boolean {
        return resultCode == Activity.RESULT_OK && resultData != null
    }
}
