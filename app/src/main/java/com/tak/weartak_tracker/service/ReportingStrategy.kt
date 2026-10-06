package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.data.Activity
import com.tak.weartak_tracker.data.TrackerConfig
import kotlin.math.max

/** Same constant/dynamic reporting interval rules as WearTAK-CIV's ReportingIntervalStrategyService. */
object ReportingStrategy {
    fun intervalSecs(config: TrackerConfig, alerting: Boolean, activity: Activity): Int =
        if (config.dynamicReporting) dynamicInterval(config, alerting, activity) else config.constantInterval

    fun dynamicInterval(config: TrackerConfig, alerting: Boolean, activity: Activity): Int {
        if (alerting) return config.alertingInterval
        return when (activity) {
            Activity.ON_FOOT -> config.onFootInterval
            Activity.IN_VEHICLE -> config.vehicleInterval
            Activity.UNKNOWN -> max(config.onFootInterval, config.vehicleInterval)
            Activity.STILL -> config.stationaryInterval
        }
    }
}
