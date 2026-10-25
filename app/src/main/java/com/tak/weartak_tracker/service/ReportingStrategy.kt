package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.data.TrackerConfig
import kotlin.math.max
import kotlin.math.floor

/** Saved reporting rates with ATAK-style moving-speed interpolation. */
object ReportingStrategy {
    const val MOTION_BOUNDARY_MPS = 0.44704
    const val VEHICLE_BOUNDARY_MPS = 13.4112
    const val DYNAMIC_OBSERVATION_CAP_SECS = 60

    fun intervalForSpeedSecs(config: TrackerConfig, alerting: Boolean, speedMps: Double?): Int {
        if (!config.dynamicReporting) return config.constantInterval
        if (alerting) return config.alertingInterval
        if (speedMps == null || !speedMps.isFinite() || speedMps < 0) {
            return max(config.onFootInterval, config.vehicleInterval)
        }

        if (speedMps < MOTION_BOUNDARY_MPS) return config.stationaryInterval
        if (speedMps >= VEHICLE_BOUNDARY_MPS) return config.vehicleInterval
        val fraction = ((speedMps - MOTION_BOUNDARY_MPS) /
            (VEHICLE_BOUNDARY_MPS - MOTION_BOUNDARY_MPS)).coerceIn(0.0, 1.0)
        return floor(config.onFootInterval +
            (config.vehicleInterval.toDouble() - config.onFootInterval) * fraction + 0.5).toInt()
            .coerceIn(minOf(config.onFootInterval, config.vehicleInterval), max(config.onFootInterval, config.vehicleInterval))
    }

    fun observationIntervalSecs(config: TrackerConfig, selectedIntervalSecs: Int): Int {
        return if (config.dynamicReporting) {
            minOf(selectedIntervalSecs, DYNAMIC_OBSERVATION_CAP_SECS, config.onFootInterval, config.vehicleInterval)
        } else selectedIntervalSecs
    }

    fun speedValidUntil(sampleElapsed: Long, observationIntervalSecs: Int, nextObservationElapsed: Long?, timeoutMillis: Long): Long {
        val nextBySample = sampleElapsed + observationIntervalSecs.toLong() * 1000L
        return minOf(nextBySample, nextObservationElapsed ?: nextBySample) + timeoutMillis
    }
}

/** Speed observations may change the deadline, but never reset elapsed time since the last PLI. */
internal class ReportingCadence {
    private var lastSentElapsed: Long? = null

    fun isDue(nowElapsed: Long, intervalSecs: Int): Boolean =
        lastSentElapsed?.let { nowElapsed >= it && nowElapsed - it >= intervalSecs.toLong() * 1000L } ?: true

    fun onSent(nowElapsed: Long) {
        lastSentElapsed = nowElapsed
    }
}
