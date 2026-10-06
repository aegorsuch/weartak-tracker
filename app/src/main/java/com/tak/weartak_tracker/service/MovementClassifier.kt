package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.data.Activity

/**
 * Classifies movement from GPS speed, as ATAK does, with hysteresis so a single noisy fix or a stop at a
 * light does not flip the reporting interval. Leaving STILL is normally triggered by the motion sensor
 * ([onMotion]) because a stationary watch only takes a fix once per stationary interval.
 */
class MovementClassifier(
    private val stillBelowMps: Float = STILL_BELOW_MPS,
    private val vehicleAboveMps: Float = VEHICLE_ABOVE_MPS,
) {
    var current: Activity = Activity.UNKNOWN
        private set
    private var candidate: Activity? = null
    private var count = 0

    /** Feeds one fix's speed (null when the fix has no usable speed); returns the resulting state. */
    fun onSpeed(speedMps: Float?): Activity {
        if (speedMps == null || speedMps.isNaN() || speedMps < 0f) return current
        val observed = classify(speedMps)
        if (observed == current) {
            candidate = null
            count = 0
            return current
        }
        if (observed == candidate) count++ else {
            candidate = observed
            count = 1
        }
        // UNKNOWN is not a confident state, so the first real observation replaces it immediately.
        val needed = when {
            current == Activity.UNKNOWN -> 1
            observed == Activity.STILL -> CONFIRM_STILL
            else -> CONFIRM_MOVING
        }
        if (count >= needed) switchTo(observed)
        return current
    }

    /** Significant motion detected: stop treating the device as stationary until speed says otherwise. */
    fun onMotion(): Activity {
        if (current == Activity.STILL) switchTo(Activity.UNKNOWN)
        return current
    }

    fun reset() = switchTo(Activity.UNKNOWN)

    private fun switchTo(activity: Activity) {
        current = activity
        candidate = null
        count = 0
    }

    private fun classify(speed: Float): Activity = when {
        speed < stillBelowMps -> Activity.STILL
        speed > vehicleAboveMps -> Activity.IN_VEHICLE
        else -> Activity.ON_FOOT
    }

    companion object {
        /** ~1.8 km/h: below normal walking pace, above typical GPS jitter at rest. */
        const val STILL_BELOW_MPS = 0.5f
        /** ~16 km/h: above running pace. */
        const val VEHICLE_ABOVE_MPS = 4.5f
        /** Entering STILL needs more evidence so short stops keep the moving interval. */
        const val CONFIRM_STILL = 3
        const val CONFIRM_MOVING = 2
    }
}
