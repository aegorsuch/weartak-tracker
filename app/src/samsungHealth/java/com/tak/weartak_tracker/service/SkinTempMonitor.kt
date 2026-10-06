package com.tak.weartak_tracker.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

/**
 * Skin temperature from the Samsung Health Sensor SDK, read like WearTAK-CIV's SkinTempService: one on-demand
 * reading per [INTERVAL_MS] while worn, reported in °F. Watches without Samsung Health Platform never connect,
 * and the value stays unavailable.
 */
internal class SkinTempMonitor(context: Context, private val onChanged: () -> Unit) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var service: HealthTrackingService? = null
    private var tracker: HealthTracker? = null
    private var enabled = false
    private var worn = true

    @Volatile private var lastTempF = Float.NaN
    @Volatile private var lastSampleElapsed = 0L

    /** Latest skin temperature in °F, or null when off, off-wrist, unsupported, or stale. */
    val tempF: Float?
        get() = lastTempF.takeIf {
            enabled && worn && !it.isNaN() && SystemClock.elapsedRealtime() - lastSampleElapsed <= MAX_SAMPLE_AGE_MS
        }

    private val measure = Runnable { startReading() }

    private val listener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            stopReading()
            points.firstOrNull()?.let { dp ->
                if (dp.getValue(ValueKey.SkinTemperatureSet.STATUS) >= 0) {
                    lastTempF = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE) * 9f / 5f + 32f
                    lastSampleElapsed = SystemClock.elapsedRealtime()
                } else {
                    lastTempF = Float.NaN
                }
                onChanged()
            }
            schedule()
        }

        override fun onFlushCompleted() = Unit

        override fun onError(error: HealthTracker.TrackerError) {
            stopReading()
            if (error == HealthTracker.TrackerError.SDK_POLICY_ERROR) {
                // Samsung partner approval is per package; retrying won't help until this app id is approved.
                Log.w(TAG, "Skin temperature blocked by Samsung SDK policy; app id not approved")
                return
            }
            Log.w(TAG, "Skin temperature tracker error: $error")
            schedule()
        }
    }

    private val connection = object : ConnectionListener {
        override fun onConnectionSuccess() {
            val s = service ?: return
            val supported = runCatching {
                HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND in s.trackingCapability.supportHealthTrackerTypes
            }.getOrDefault(false)
            if (!supported) {
                Log.i(TAG, "Skin temperature is not supported on this watch")
                return
            }
            tracker = runCatching { s.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND) }
                .onFailure { Log.w(TAG, "Skin temperature tracker unavailable", it) }
                .getOrNull()
            if (enabled) startReading()
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.i(TAG, "Samsung Health Platform unavailable (${e.errorCode}); skin temperature disabled")
            disconnect()
        }

        override fun onConnectionEnded() {
            tracker = null
            handler.removeCallbacks(measure)
        }
    }

    fun setEnabled(on: Boolean) = handler.post {
        if (on == enabled) return@post
        enabled = on
        if (on) {
            runCatching { HealthTrackingService(connection, appContext).also { service = it }.connectService() }
                .onFailure { Log.i(TAG, "Samsung Health Sensor SDK unavailable", it); service = null }
        } else {
            disconnect()
            lastTempF = Float.NaN
            onChanged()
        }
    }

    /** Off-wrist readings are meaningless, so sampling pauses until the watch is worn again. */
    fun setWorn(isWorn: Boolean) = handler.post {
        if (isWorn == worn) return@post
        worn = isWorn
        if (!isWorn) {
            stopReading()
            handler.removeCallbacks(measure)
            lastTempF = Float.NaN
        } else if (enabled && tracker != null) {
            startReading()
        }
    }

    private fun startReading() {
        val t = tracker ?: return
        if (!enabled || !worn) return
        runCatching { t.setEventListener(listener) }.onFailure { Log.w(TAG, "Skin temperature read failed", it); schedule() }
    }

    private fun stopReading() {
        runCatching { tracker?.unsetEventListener() }
    }

    private fun schedule() {
        handler.removeCallbacks(measure)
        if (enabled && worn) handler.postDelayed(measure, INTERVAL_MS)
    }

    private fun disconnect() {
        handler.removeCallbacks(measure)
        stopReading()
        tracker = null
        runCatching { service?.disconnectService() }
        service = null
    }

    private companion object {
        const val TAG = "SkinTempMonitor"
        const val INTERVAL_MS = 60_000L
        const val MAX_SAMPLE_AGE_MS = 10 * 60_000L
    }
}
