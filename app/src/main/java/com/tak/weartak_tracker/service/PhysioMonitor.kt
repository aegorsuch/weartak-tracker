package com.tak.weartak_tracker.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.tak.weartak_tracker.data.TrackerState

/**
 * Heart rate for physio PLI, read like WearTAK-CIV's HeartrateService: TYPE_HEART_RATE samples are used
 * only at high accuracy, and the value is cleared while the off-body sensor reports the watch off-wrist.
 */
class PhysioMonitor(private val context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val heartRate: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private val offBody: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)

    @Volatile private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    @Volatile private var worn = true
    @Volatile private var lastBpm = -1
    @Volatile private var lastSampleElapsed = 0L
    private var running = false
    private val skin = SkinTempMonitor(context) { publish() }

    /** Latest skin temperature in degrees F, or null when unavailable (non-Samsung watch, off-wrist, or stopped). */
    val skinTempF: Float? get() = skin.tempF

    val available: Boolean get() = heartRate != null

    /** Latest valid heart rate, or -1 when off-wrist, stopped, or no recent reliable sample. */
    val bpm: Int
        get() = if (running && worn && lastBpm > 0 &&
            SystemClock.elapsedRealtime() - lastSampleElapsed <= MAX_SAMPLE_AGE_MS
        ) lastBpm else -1

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, value: Int) {
            if (sensor.type == Sensor.TYPE_HEART_RATE) accuracy = value
        }

        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT -> {
                    worn = event.values.firstOrNull() == 1f
                    if (!worn) lastBpm = -1
                    skin.setWorn(worn)
                    publish()
                }
                Sensor.TYPE_HEART_RATE -> {
                    val value = event.values.firstOrNull()?.toInt() ?: return
                    if (event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_HIGH) accuracy = event.accuracy
                    if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_HIGH || !worn || value <= 0) return
                    lastBpm = value
                    lastSampleElapsed = SystemClock.elapsedRealtime()
                    publish()
                }
            }
        }
    }

    fun permissionGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops sampling; returns whether heart rate is now being read. */
    fun setEnabled(enabled: Boolean): Boolean {
        val want = enabled && heartRate != null && permissionGranted()
        if (want == running) return running
        val manager = sensors ?: return false
        if (want) {
            worn = true
            accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
            running = manager.registerListener(listener, heartRate, SAMPLING_PERIOD_US)
            offBody?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
            if (!running) Log.w(TAG, "Heart rate sensor registration failed")
            skin.setEnabled(running)
        } else {
            manager.unregisterListener(listener)
            running = false
            lastBpm = -1
            skin.setEnabled(false)
        }
        publish()
        return running
    }

    private fun publish() {
        TrackerState.heartRate.value = bpm
        TrackerState.skinTempF.value = skinTempF
    }

    private companion object {
        const val TAG = "PhysioMonitor"
        const val SAMPLING_PERIOD_US = 1_000_000
        /** A heart rate older than this is reported as N/A rather than as a stale value. */
        const val MAX_SAMPLE_AGE_MS = 5 * 60_000L
    }
}
