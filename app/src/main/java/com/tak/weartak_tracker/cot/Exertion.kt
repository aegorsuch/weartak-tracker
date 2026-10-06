package com.tak.weartak_tracker.cot

/** Karvonen HR-reserve fraction, using WearTAK's age-based maximum HR estimate. */
fun calculateExertion(currentHr: Int?, ageYears: Int?, restingHr: Int): Double? {
    if (currentHr == null || currentHr <= 0 || ageYears == null || ageYears < 0 || restingHr <= 0) return null
    val reserve = 208.0 - 0.7 * ageYears - restingHr
    if (reserve <= 0) return null
    return (currentHr - restingHr).coerceAtLeast(0) / reserve
}
