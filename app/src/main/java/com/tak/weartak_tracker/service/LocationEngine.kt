package com.tak.weartak_tracker.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.tak.weartak_tracker.data.LocationAccess
import com.tak.weartak_tracker.diagnostics.Diagnostics
import com.tak.weartak_tracker.diagnostics.DiagnosticEvent
import com.tak.weartak_tracker.diagnostics.DiagnosticDetail
import com.tak.weartak_tracker.diagnostics.AppLog as Log

/**
 * GPS scheduling as in WearTAK-CIV's LocationService: short intervals (<10 s) use a periodic request;
 * longer intervals wake via an alarm clock, take a wakelock and request a single high-accuracy fix.
 */
class LocationEngine(
    private val context: Context,
    private val onLocation: (Location, Boolean) -> Unit,
    private val onNoFix: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var fixSinceAlarm = true
    private val noFixCheck = Runnable {
        if (!fixSinceAlarm) {
            Diagnostics.record(DiagnosticEvent.LOCATION, DiagnosticDetail.TIMEOUT)
            onNoFix()
        }
        releaseWakeLock()
    }

    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null
    private var intervalSecs = 0
    private var periodic = false
    private var access = LocationAccess.NONE
    private var oneShotSince = 0L
    private var forcePliOnFix = false
    private var nextObservationElapsed = 0L
    var inputStartedElapsed = 0L
        private set

    private val periodicCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocation(it, false) }
        }
    }

    private val oneShotCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            fixSinceAlarm = true
            oneShotSince = 0L
            handler.removeCallbacks(noFixCheck)
            releaseWakeLock()
            val forced = forcePliOnFix
            forcePliOnFix = false
            result.lastLocation?.let { onLocation(it, forced) }
        }
    }

    private val alarmIntent: PendingIntent by lazy {
        PendingIntent.getService(
            context, 1,
            Intent(context, TrackerService::class.java).setAction(TrackerService.ACTION_LOCATION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun start(intervalSecs: Int) {
        val initial = this.intervalSecs == 0
        val currentAccess = LocationAccess.current(context)
        if (initial || currentAccess != access) {
            inputStartedElapsed = SystemClock.elapsedRealtime()
            fused.removeLocationUpdates(oneShotCallback)
            oneShotSince = 0L
            handler.removeCallbacks(noFixCheck)
            releaseWakeLock()
        }
        fused.removeLocationUpdates(periodicCallback)
        alarms.cancel(alarmIntent)
        this.intervalSecs = intervalSecs
        access = currentAccess
        periodic = intervalSecs < PERIODIC_THRESHOLD_SECS
        if (periodic) {
            val ms = intervalSecs * 1000L
            val request = LocationRequest.Builder(access.priority, ms)
                .setMinUpdateIntervalMillis(ms)
                .build()
            requestUpdates(request, periodicCallback)
            if (forcePliOnFix) requestSingleFix(forcePli = false)
        } else {
            if (initial) requestSingleFix(forcePli = false)
            scheduleAlarm()
        }
    }

    /** Alarm fired: take a fresh fix and schedule the next one. */
    fun onAlarm() {
        if (periodic || intervalSecs == 0) return
        requestSingleFix(forcePli = false)
        // If no fix arrives (e.g. indoors) still report the last known position for this cycle.
        fixSinceAlarm = false
        handler.removeCallbacks(noFixCheck)
        handler.postDelayed(noFixCheck, NO_FIX_FALLBACK_MS)
        scheduleAlarm()
    }

    /** Re-applies the current interval when the user switched between precise and approximate location. */
    fun refreshAccess() {
        if (intervalSecs > 0 && LocationAccess.current(context) != access) {
            start(intervalSecs)
            requestSingleFix(forcePli = false)
        }
    }

    /**
     * Active high-accuracy fix for alerts, identity changes, manual PLI and start-up, without changing the
     * reporting schedule. While one is still in flight (bounded by [ONE_SHOT_IN_FLIGHT_MS]) repeat calls are
     * dropped, so repeated taps don't stack requests; the pending fix serves them all.
     */
    fun requestSingleFix(forcePli: Boolean = true) {
        forcePliOnFix = forcePliOnFix || forcePli
        val now = SystemClock.elapsedRealtime()
        if (oneShotSince != 0L && now - oneShotSince < ONE_SHOT_IN_FLIGHT_MS) return
        oneShotSince = now
        releaseWakeLock()
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WearTAKTracker:gps").apply {
            acquire(WAKELOCK_MS)
        }
        val current = LocationAccess.current(context)
        val request = LocationRequest.Builder(current.priority, 1000)
            .setMaxUpdates(1)
            .setMinUpdateIntervalMillis(0)
            .setMaxUpdateAgeMillis(0)
            .setMaxUpdateDelayMillis(1000)
            .setWaitForAccurateLocation(current == LocationAccess.PRECISE)
            .build()
        fused.removeLocationUpdates(oneShotCallback)
        requestUpdates(request, oneShotCallback)
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(callback: (Location?) -> Unit) {
        runCatching { fused.lastLocation.addOnSuccessListener { callback(it) }.addOnFailureListener { callback(null) } }
            .onFailure { callback(null) }
    }

    fun stop() {
        fused.removeLocationUpdates(periodicCallback)
        fused.removeLocationUpdates(oneShotCallback)
        oneShotSince = 0L
        alarms.cancel(alarmIntent)
        handler.removeCallbacks(noFixCheck)
        releaseWakeLock()
        intervalSecs = 0
        forcePliOnFix = false
        nextObservationElapsed = 0L
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(request: LocationRequest, callback: LocationCallback) {
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w("LocationEngine", "Location request rejected", e)
            releaseWakeLock()
        }
    }

    private fun scheduleAlarm() {
        val delay = intervalSecs * 1000L - ALARM_EARLY_MS
        nextObservationElapsed = SystemClock.elapsedRealtime() + delay
        if (alarms.canScheduleExactAlarms()) {
            val trigger = System.currentTimeMillis() + delay
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, null), alarmIntent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, alarmIntent)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    fun speedValidUntil(sampleElapsed: Long): Long =
        ReportingStrategy.speedValidUntil(
            sampleElapsed, intervalSecs,
            if (periodic) null else nextObservationElapsed,
            ONE_SHOT_IN_FLIGHT_MS,
        )

    companion object {
        const val PERIODIC_THRESHOLD_SECS = 10
        private const val ALARM_EARLY_MS = 35L
        private const val WAKELOCK_MS = 10_000L
        private const val NO_FIX_FALLBACK_MS = 9_000L
        /** Matches the wakelock: after this an unanswered one-shot is assumed lost and may be re-requested. */
        private const val ONE_SHOT_IN_FLIGHT_MS = WAKELOCK_MS
    }
}
