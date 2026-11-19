package com.tak.weartak_tracker.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tak.weartak_tracker.BuildConfig
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DiagnosticsStatus { OFF, RECORDING, UNAVAILABLE }

object Diagnostics {
    private const val TAG = "TrackerDiagnostics"
    private val consent = DiagnosticsConsent()
    private var context: Context? = null
    private var journal: DiagnosticsJournal? = null
    private var lastErrorElapsed: Long? = null
    private val mutableStatus = MutableStateFlow(DiagnosticsStatus.OFF)
    val status = mutableStatus.asStateFlow()
    private val writer = ThreadPoolExecutor(
        0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "TrackerDiagnostics").apply { isDaemon = true } },
    )

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (!consent.setEnabled(value)) return
        mutableStatus.value = if (value) DiagnosticsStatus.RECORDING else DiagnosticsStatus.OFF
        if (value) record(DiagnosticEvent.DIAGNOSTICS, DiagnosticDetail.ENABLED)
    }

    fun record(event: DiagnosticEvent, detail: DiagnosticDetail) {
        val capturedGeneration = consent.token() ?: return
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        try {
            writer.execute {
                if (!consent.permits(capturedGeneration)) return@execute
                try {
                    sink().record(event, detail, wall, elapsed)
                } catch (error: IOException) {
                    failed(elapsed, error)
                } catch (error: SecurityException) {
                    failed(elapsed, error)
                }
            }
        } catch (error: RejectedExecutionException) {
            failed(elapsed, error)
        }
    }

    /** Preserve only a crash marker before delegating to Android's unchanged crash handler. */
    fun recordCrash() {
        if (!consent.enabled) return
        val elapsed = SystemClock.elapsedRealtime()
        try {
            sink().record(DiagnosticEvent.CRASH, DiagnosticDetail.FAILED, System.currentTimeMillis(), elapsed)
        } catch (error: IOException) {
            failed(elapsed, error)
        } catch (error: SecurityException) {
            failed(elapsed, error)
        }
    }

    @Synchronized
    private fun sink(): DiagnosticsJournal = journal ?: DiagnosticsJournal(
        context?.getExternalFilesDir("diagnostics") ?: throw IOException("App-local diagnostics storage unavailable"),
        BuildConfig.VERSION_CODE,
    ).also { journal = it }

    @Synchronized
    private fun failed(elapsed: Long, error: Exception) {
        if (consent.enabled) mutableStatus.value = DiagnosticsStatus.UNAVAILABLE
        val last = lastErrorElapsed
        if (last == null || elapsed - last >= DiagnosticsJournal.REPETITION_WINDOW_MS) {
            lastErrorElapsed = elapsed
            Log.e(TAG, "Some diagnostic events could not be saved", error)
        }
    }
}
