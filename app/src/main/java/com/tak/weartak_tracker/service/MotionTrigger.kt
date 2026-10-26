package com.tak.weartak_tracker.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import com.tak.weartak_tracker.diagnostics.AppLog as Log

/**
 * One-shot significant-motion trigger (low-power wake-up sensor, no runtime permission). Armed while the
 * tracker is STILL so movement is noticed without waiting for the next stationary-interval fix.
 */
class MotionTrigger(context: Context, private val onMotion: () -> Unit) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var armed = false
    private var failureReported = false

    val available: Boolean get() = sensor != null

    private val listener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // The sensor disarms itself after firing.
            armed = false
            onMotion()
        }
    }

    fun arm(): Boolean {
        if (armed) return true
        if (failureReported) return false
        if (sensor == null) {
            reportUnavailable()
            return false
        }
        armed = sensors?.requestTriggerSensor(listener, sensor) == true
        if (!armed) reportUnavailable() else failureReported = false
        return armed
    }

    fun disarm() {
        if (armed && sensor != null) sensors?.cancelTriggerSensor(listener, sensor)
        armed = false
        failureReported = false
    }

    private fun reportUnavailable() {
        if (failureReported) return
        failureReported = true
        Log.w("MotionTrigger", "Significant motion unavailable or could not be armed; retaining stationary GPS polling")
    }
}
