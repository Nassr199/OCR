package com.abdulrahman.hardsubocr

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import com.abdulrahman.hardsubocr.core.DiagLog
import com.abdulrahman.hardsubocr.core.stackTraceOf
import com.abdulrahman.hardsubocr.data.FileOcrCache
import com.abdulrahman.hardsubocr.data.Settings
import com.abdulrahman.hardsubocr.job.Notifications
import com.abdulrahman.hardsubocr.net.Net
import java.io.File

class App : Application() {

    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        DiagLog.enabled = settings.diagnostics
        DiagLog.init(File(filesDir, "diagnostics"), deviceInfo())
        installCrashReporter()
        Notifications.createChannels(this)
        Net.init(this)
        // frames of a job the system killed: nothing is running yet in a fresh process
        Thread({ runCatching { FileOcrCache.cleanTemp(this) } }, "temp-cleanup").apply { isDaemon = true }.start()
    }

    /** App, device and memory facts written at the top of every session of the diagnostic log. */
    fun deviceInfo(): String {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return buildString {
            appendLine("الإصدار: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} • المعالج: $soc")
            appendLine("أندرويد ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine(
                "الذاكرة: ${mi.totalMem / 1048576} MB، المتاح ${mi.availMem / 1048576} MB • حد التطبيق ${Runtime.getRuntime().maxMemory() / 1048576} MB" +
                    " (largeHeap ${am.largeMemoryClass} MB)"
            )
        }
    }

    /** Keeps the stack trace of an unexpected crash so the next start can show / copy / send it. */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { DiagLog.crash(thread.name, e) }
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
