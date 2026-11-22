package com.abdulrahman.hardsubocr.job

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Process-wide job holder: the UI observes [state], the foreground service runs the job. */
object JobManager {
    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()

    @Volatile
    private var pending: JobRequest? = null

    @Volatile
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true || pending != null

    fun start(context: Context, request: JobRequest): Boolean {
        if (isRunning) return false
        pending = request
        _state.value = JobState.Running(Progress.starting())
        return try {
            ContextCompat.startForegroundService(context, Intent(context, ExtractionService::class.java))
            true
        } catch (e: Exception) {
            pending = null
            _state.value = JobState.Error("تعذر بدء خدمة الاستخراج: ${e.message}", emptyList())
            false
        }
    }

    fun stop() {
        job?.cancel()
        if (job == null && pending != null) {
            pending = null
            _state.value = JobState.Idle
        }
    }

    fun dismiss() {
        if (!isRunning) _state.value = JobState.Idle
    }

    /** Lets the result editor replace an output (after editing / saving). */
    fun replaceOutput(index: Int, output: JobOutput) {
        when (val s = _state.value) {
            is JobState.Done -> if (index in s.outputs.indices) {
                _state.value = s.copy(outputs = s.outputs.toMutableList().also { it[index] = output })
            }
            is JobState.Error -> if (index in s.outputs.indices) {
                _state.value = s.copy(outputs = s.outputs.toMutableList().also { it[index] = output })
            }
            else -> Unit
        }
    }

    internal fun runIn(scope: CoroutineScope, context: Context, onFinished: (JobState) -> Unit) {
        val req = pending ?: run {
            onFinished(_state.value); return
        }
        job = scope.launch {
            try {
                JobRunner(context, req) { _state.value = it }.run()
            } catch (e: CancellationException) {
                // the runner already published the partial result
            } catch (e: Throwable) {
                _state.value = JobState.Error("خطأ غير متوقع: ${e.message ?: e.javaClass.simpleName}", emptyList())
            } finally {
                pending = null
                job = null
                onFinished(_state.value)
            }
        }
        pending = null
    }
}
