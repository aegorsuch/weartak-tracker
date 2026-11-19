package com.tak.weartak_tracker.service

/** Cached speed for report selection only; validity checks never initiate location acquisition. */
internal data class ReportingSpeed(
    val metersPerSecond: Double?,
    val acquiredElapsedMillis: Long?,
    val provider: String?,
    val validUntilElapsedMillis: Long? = null,
) {
    fun current(nowElapsed: Long, currentProvider: String?, inputStartedElapsed: Long = 0L): Double? {
        val speed = metersPerSecond ?: return null
        val sample = acquiredElapsedMillis ?: return null
        val deadline = validUntilElapsedMillis ?: return null
        if (!speed.isFinite() || speed < 0 || sample <= 0 || sample > nowElapsed || sample < inputStartedElapsed) return null
        if (provider.isNullOrBlank() || provider != currentProvider || deadline < sample) return null
        return speed.takeIf { nowElapsed <= deadline }
    }
}
