package com.abdulrahman.hardsubocr

import android.app.Application
import com.abdulrahman.hardsubocr.core.stackTraceOf
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
        installCrashReporter()
        Notifications.createChannels(this)
        Net.init(this)
    }

    /** Keeps the stack trace of an unexpected crash so the next start can show / copy it. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                getSharedPreferences("hardsubocr_settings", MODE_PRIVATE).edit()
                    .putString("last_error_report", "Thread: ${thread.name}\n" + stackTraceOf(e))
                    .commit()
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
