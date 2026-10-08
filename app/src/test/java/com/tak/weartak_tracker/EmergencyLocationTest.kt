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
    fun absentStaleFutureOrInvalidLocationUsesUnknownPositionForAlertsAndCancels() {
        val unusable = listOf(
            null,
            fix.copy(timeMillis = nowMillis - 3_000),
            fix.copy(timeMillis = nowMillis - 600_000),
            fix.copy(timeMillis = nowMillis + 1),
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
