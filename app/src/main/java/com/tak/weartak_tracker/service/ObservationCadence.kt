package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.data.Activity
import com.tak.weartak_tracker.data.TrackerConfig

/** Applies activity profiles independently of speed-based PLI dispatch. */
internal class ObservationCadence(
    private val armMotion: () -> Boolean,
    private val disarmMotion: () -> Unit,
    private val schedule: (Int) -> Unit,
    private val requestFix: () -> Unit,
    private val stopLocation: () -> Unit,
) {
    private var interval: Int? = null
    private var stopped = false

    fun update(config: TrackerConfig, activity: Activity, alerting: Boolean, freshFix: Boolean = false) {
        if (stopped) return
        val guarded = if (config.dynamicReporting && !alerting && activity == Activity.STILL) {
            armMotion()
        } else {
            disarmMotion()
            false
        }
        val next = ReportingStrategy.observationIntervalSecs(config, activity, alerting, guarded)
        val previous = interval
        if (next != previous) {
            interval = next
            schedule(next)
        }
        // Replacing a long stationary alarm must not delay movement, alert or faster-profile fixes.
        if (freshFix || (previous != null && next < previous)) requestFix()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        interval = null
        disarmMotion()
        stopLocation()
    }
}
