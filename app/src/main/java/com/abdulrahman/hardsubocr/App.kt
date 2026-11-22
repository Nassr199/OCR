package com.abdulrahman.hardsubocr

import android.app.Application
import com.abdulrahman.hardsubocr.data.Settings
import com.abdulrahman.hardsubocr.job.Notifications
import com.abdulrahman.hardsubocr.net.Net

class App : Application() {

    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        Notifications.createChannels(this)
        Net.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
