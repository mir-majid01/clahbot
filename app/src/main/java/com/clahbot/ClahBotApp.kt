package com.clahbot

import android.app.Application
import com.clahbot.data.SharedVisionBuffer
import com.clahbot.data.db.AppDatabase
import com.clahbot.monitor.WatchdogManager

class ClahBotApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        WatchdogManager.init(this)
    }

    companion object {
        lateinit var instance: ClahBotApp
            private set

        val visionBuffer: SharedVisionBuffer
            get() = SharedVisionBuffer
    }
}
