package com.clahbot.service

import android.graphics.Bitmap

object FrameCaptureHolder {
    @Volatile
    var latestFrame: Bitmap? = null

    fun swap(): Bitmap? {
        val old = latestFrame
        latestFrame = null
        return old
    }
}
