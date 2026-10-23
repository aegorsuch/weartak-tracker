package com.tak.weartak_tracker

import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.cot.Fix
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyLocationTest {
    private val nowMillis = 10_000L
    private val fix = Fix(38.0, -77.0, 100.0, 5f, 8f, 0f, 0f, nowMillis)
    private val unknown = "<point lat='0.0' lon='0.0' hae='0.0' ce='9999999.0' le='9999999.0'/>"

    private fun emergency(location: Fix?, state: AlertState = AlertState.ALERT) = CotBuilder.emergency(
        "alert", state, "Manual Alert", "Injury", 1, "device", "Watch",
        location, CotTime.now(CotBuilder.ALERT_STALE_SECONDS, nowMillis),
    )

    @Test
    fun freshLocationIsIncludedWithoutWaitingForANewFix() {
        listOf(0L, 2_999L).forEach { age ->
            assertTrue(emergency(fix.copy(timeMillis = nowMillis - age)).contains(
                "<point lat='38.0' lon='-77.0' hae='100.0' ce='5.0' le='8.0'/>",
            ))
        }
    }

    @Test
    fun olderFixWithinBoundIsUsedWithErrorGrownByElapsedTime() {
        // 60 s old at walking pace: 5 + 1.5 * 60 = 95 m.
        assertTrue(emergency(fix.copy(timeMillis = nowMillis - 60_000)).contains(
            "<point lat='38.0' lon='-77.0' hae='100.0' ce='95.0' le='8.0'/>",
        ))
        // Faster recorded speed grows the error faster: 5 + 10 * 10 = 105 m.
        assertTrue(emergency(fix.copy(speed = 10f, timeMillis = nowMillis - 10_000)).contains("ce='105.0'"))
        // Up to the bound, still the last known position.
        assertTrue(emergency(fix.copy(timeMillis = nowMillis - CotBuilder.MAX_EMERGENCY_FIX_AGE_MILLIS)).contains("lat='38.0'"))
        // Slight GPS/system clock skew counts as current.
        assertTrue(emergency(fix.copy(timeMillis = nowMillis + 5_000)).contains("ce='5.0'"))
    }

    @Test
    fun absentTooOldFarFutureOrInvalidLocationUsesUnknownPositionForAlertsAndCancels() {
        val unusable = listOf(
            null,
            fix.copy(timeMillis = nowMillis - CotBuilder.MAX_EMERGENCY_FIX_AGE_MILLIS - 1),
            fix.copy(timeMillis = nowMillis - 3_600_000),
            fix.copy(timeMillis = nowMillis + 60_001),
            fix.copy(lat = Double.NaN),
            fix.copy(lat = 90.1),
            fix.copy(lon = Double.POSITIVE_INFINITY),
            fix.copy(lon = -180.1),
        )
        for (state in AlertState.entries) {
            unusable.forEach { assertTrue(emergency(it, state).contains(unknown)) }
        }
    }

    @Test
    fun invalidAltitudeAndErrorsDoNotInvalidateGoodCoordinates() {
        val xml = emergency(fix.copy(hae = Double.NaN, ce = Float.POSITIVE_INFINITY, le = -1f))
        assertTrue(xml.contains("<point lat='38.0' lon='-77.0' hae='0.0' ce='9999999.0' le='9999999.0'/>"))
        assertFalse(xml.contains("NaN"))
        assertFalse(xml.contains("Infinity"))
        assertTrue(emergency(fix.copy(ce = 0f, le = 0f)).contains("ce='0.0' le='0.0'"))
    }
}
