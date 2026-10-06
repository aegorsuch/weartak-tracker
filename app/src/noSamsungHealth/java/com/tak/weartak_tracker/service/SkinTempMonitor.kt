package com.tak.weartak_tracker.service

import android.content.Context

/**
 * Build without the Samsung Health Sensor SDK (app/libs/samsung-health-sensor-api-*.aar absent):
 * skin temperature is never available and is reported as N/A.
 */
@Suppress("UNUSED_PARAMETER")
internal class SkinTempMonitor(context: Context, onChanged: () -> Unit) {
    val tempF: Float? get() = null
    fun setEnabled(on: Boolean) = Unit
    fun setWorn(isWorn: Boolean) = Unit
}
