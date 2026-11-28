package com.abdulrahman.hardsubocr.job

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.abdulrahman.hardsubocr.core.Timestamps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps long extractions alive while the screen is off or the app is in the background. */
class ExtractionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            JobManager.stop()
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (!started) {
            started = true
            acquireWakeLock()
            scope.launch {
                JobManager.state.collect { st ->
                    if (st is JobState.Running) {
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 1000) {
                            lastNotify = now
                            val p = st.progress
                            val pct = (p.fraction * 100).toInt()
                            val text = buildString {
                                if (p.phase.isNotEmpty()) append(p.phase).append('\n')
                                append(
                                    if (p.stage == 1) "${p.subtitlesFound} صورة ترجمة حتى الآن"
                                    else "${p.done}/${p.total}  •  ✓${p.ok}  ○${p.empty}  ✖${p.failed}"
                                )
                                if (!p.etaSec.isNaN()) append("  •  المتبقي ").append(Timestamps.human(p.etaSec))
                            }
                            Notifications.notify(
                                this@ExtractionService, Notifications.ID_PROGRESS,
                                Notifications.progress(this@ExtractionService, p.title.ifEmpty { "استخراج الترجمة" } + " — $pct%", text, pct, p.total == 0 && p.decodeFraction == null)
                            )
                        }
                    }
                }
            }
            JobManager.runIn(scope, applicationContext) { final -> finish(final) }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = Notifications.progress(this, "استخراج الترجمة", "جاري التحضير…", 0, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.ID_PROGRESS, n)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HardSubOCR:extract").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun finish(final: JobState) {
        when (final) {
            is JobState.Done -> Notifications.done(
                this, if (final.stopped) "تم إيقاف الاستخراج" else "✓ اكتمل استخراج الترجمة",
                final.summary + " — " + Timestamps.human(final.elapsedSec)
            )
            is JobState.Error -> Notifications.done(this, "تعذر إكمال الاستخراج", final.message)
            else -> Unit
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15: dataSync services get 6 h per day. Stop cleanly, partial results are kept. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        JobManager.stop()
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.abdulrahman.hardsubocr.STOP"
    }
}
