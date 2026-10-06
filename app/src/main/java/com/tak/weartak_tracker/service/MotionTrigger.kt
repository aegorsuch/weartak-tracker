package com.tak.weartak_tracker.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener

/**
 * One-shot significant-motion trigger (low-power wake-up sensor, no runtime permission). Armed while the
 * tracker is STILL so movement is noticed without waiting for the next stationary-interval fix.
 */
class MotionTrigger(context: Context, private val onMotion: () -> Unit) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var armed = false

    val available: Boolean get() = sensor != null

    private val listener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // The sensor disarms itself after firing.
            armed = false
            onMotion()
        }
    }

    fun arm() {
        if (armed || sensor == null) return
        armed = sensors?.requestTriggerSensor(listener, sensor) == true
    }

    fun disarm() {
        if (!armed || sensor == null) return
        sensors?.cancelTriggerSensor(listener, sensor)
        armed = false
    }
}
