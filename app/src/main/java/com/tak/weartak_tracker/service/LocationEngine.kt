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

/**
 * GPS scheduling as in WearTAK-CIV's LocationService: short intervals (<10 s) use a periodic request;
 * longer intervals wake via an alarm clock, take a wakelock and request a single high-accuracy fix.
 */
class LocationEngine(
    private val context: Context,
    private val onLocation: (Location) -> Unit,
    private val onNoFix: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var fixSinceAlarm = true
    private val noFixCheck = Runnable {
        if (!fixSinceAlarm) onNoFix()
        releaseWakeLock()
    }

    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var wakeLock: PowerManager.WakeLock? = null
    private var intervalSecs = 0
    private var periodic = false
    private var access = LocationAccess.NONE

    private val periodicCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(onLocation)
        }
    }

    private val oneShotCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            fixSinceAlarm = true
            handler.removeCallbacks(noFixCheck)
            releaseWakeLock()
            result.lastLocation?.let(onLocation)
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
        stop()
        this.intervalSecs = intervalSecs
        access = LocationAccess.current(context)
        periodic = intervalSecs < PERIODIC_THRESHOLD_SECS
        if (periodic) {
            val ms = intervalSecs * 1000L
            val request = LocationRequest.Builder(access.priority, ms)
                .setMinUpdateIntervalMillis(ms)
                .build()
            requestUpdates(request, periodicCallback)
        } else {
            requestSingleFix()
            scheduleAlarm()
        }
    }

    /** Alarm fired: take a fresh fix and schedule the next one. */
    fun onAlarm() {
        if (periodic || intervalSecs == 0) return
        requestSingleFix()
        // If no fix arrives (e.g. indoors) still report the last known position for this cycle.
        fixSinceAlarm = false
        handler.removeCallbacks(noFixCheck)
        handler.postDelayed(noFixCheck, NO_FIX_FALLBACK_MS)
        scheduleAlarm()
    }

    /** Re-applies the current interval when the user switched between precise and approximate location. */
    fun refreshAccess() {
        if (intervalSecs > 0 && LocationAccess.current(context) != access) start(intervalSecs)
    }

    /** Extra fix for alerts and saved identity changes, without changing the reporting schedule. */
    fun requestSingleFix() {
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
        alarms.cancel(alarmIntent)
        handler.removeCallbacks(noFixCheck)
        releaseWakeLock()
        intervalSecs = 0
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(request: LocationRequest, callback: LocationCallback) {
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            releaseWakeLock()
        }
    }

    private fun scheduleAlarm() {
        val delay = intervalSecs * 1000L - ALARM_EARLY_MS
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

    companion object {
        const val PERIODIC_THRESHOLD_SECS = 10
        private const val ALARM_EARLY_MS = 35L
        private const val WAKELOCK_MS = 10_000L
        private const val NO_FIX_FALLBACK_MS = 9_000L
    }
}
