package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.service.ReportingStrategy
import com.tak.weartak_tracker.service.ReportingCadence
import com.tak.weartak_tracker.service.ReportingSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ReportingSpeedTest {
    private val config = TrackerConfig(
        stationaryInterval = 3600, onFootInterval = 60, vehicleInterval = 10,
        constantInterval = 90, alertingInterval = 5,
    )
    private val low = ReportingStrategy.MOTION_BOUNDARY_MPS
    private val high = ReportingStrategy.VEHICLE_BOUNDARY_MPS
    private fun interval(speed: Double?, c: TrackerConfig = config, alert: Boolean = false) =
        ReportingStrategy.intervalForSpeedSecs(c, alert, speed)

    @Test
    fun boundariesAndHalfUpInterpolationMatchSharedContract() {
        assertEquals(3600, interval(0.0))
        assertEquals(3600, interval(low - 0.000001))
        assertEquals(60, interval(low))
        assertEquals(48, interval(low + (high - low) / 4))
        assertEquals(35, interval((low + high) / 2))
        assertEquals(23, interval(low + (high - low) * 3 / 4))
        assertEquals(10, interval(high))
        assertEquals(10, interval(100.0))
        val half = config.copy(onFootInterval = 20, vehicleInterval = 21)
        assertEquals(21, interval((low + high) / 2, half))
    }

    @Test
    fun unknownNeverPretendsStationaryAndConstantAndAlertOverrideWin() {
        for (speed in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            assertEquals(60, interval(speed))
            assertEquals(5, interval(speed, alert = true))
            assertEquals(90, interval(speed, config.copy(dynamicReporting = false), alert = true))
        }
        assertEquals(5, interval(0.0, alert = true))
    }

    @Test
    fun reversedAndEqualUserRatesRemainClampedAndUnchanged() {
        for ((foot, vehicle) in listOf(60 to 10, 10 to 60, 20 to 20, 1 to 1_000_000)) {
            val saved = config.copy(onFootInterval = foot, vehicleInterval = vehicle)
            assertEquals(foot, interval(low, saved))
            assertEquals(vehicle, interval(high, saved))
            var previous = foot
            for (step in 0..100) {
                val result = interval(low + (high - low) * step / 100, saved)
                assertTrue(result in minOf(foot, vehicle)..maxOf(foot, vehicle))
                assertTrue(if (vehicle >= foot) result >= previous else result <= previous)
                previous = result
            }
            assertEquals(foot, saved.onFootInterval)
            assertEquals(vehicle, saved.vehicleInterval)
        }
    }

    @Test
    fun speedObservationsNeverProduceExtraReportsOrPostponeLastSendDeadline() {
        val cadence = ReportingCadence()
        assertTrue(cadence.isDue(1_000, 3600))
        cadence.onSent(1_000)
        for (time in 2_000L..59_000L step 1_000L) {
            assertFalse(cadence.isDue(time, 60))
        }
        // A newly observed speed shortens the interval relative to the original send.
        assertTrue(cadence.isDue(31_000, 30))
        cadence.onSent(31_000)
        assertFalse(cadence.isDue(31_000, 1))
        assertFalse(cadence.isDue(30_999, 1))
        assertFalse(cadence.isDue(32_000, 60))
        assertTrue(cadence.isDue(91_000, 60))
        assertFalse(cadence.isDue(91_000, 3600))
        assertTrue(cadence.isDue(3_631_000, 3600))
    }

    @Test
    fun defaultsAndConstantRemainUnmodifiedAcrossAllValidSpeeds() {
        val defaults = TrackerConfig()
        assertEquals(60, defaults.constantInterval)
        assertEquals(60, defaults.onFootInterval)
        assertEquals(60, defaults.vehicleInterval)
        assertEquals(3600, defaults.stationaryInterval)
        assertEquals(10, defaults.alertingInterval)
        for (speed in listOf(0.0, low, (low + high) / 2, high, 1000.0)) {
            assertEquals(60, interval(speed, defaults.copy(dynamicReporting = false)))
            assertEquals(10, interval(speed, defaults, alert = true))
        }
        assertEquals(TrackerConfig(), defaults)
    }

    @Test
    fun cachedSpeedUsesAcquisitionTimeAndMatchingProviderWithoutAssumingAnExpiryPolicy() {
        val observed = ReportingSpeed(1.5, 1000L, "fused")
        for (bound in listOf(1L, 10_000L, 60_000L)) {
            val timed = observed.copy(validUntilElapsedMillis = 1000 + bound)
            assertEquals(1.5, timed.current(1000 + bound, "fused"))
            assertEquals(null, timed.current(1001 + bound, "fused"))
        }
        val valid = observed.copy(validUntilElapsedMillis = 2000)
        assertEquals(null, valid.current(999, "fused"))
        assertEquals(null, valid.current(1000, "gps"))
        assertEquals(null, valid.current(1000, null))
        assertEquals(null, valid.current(1000, "fused", inputStartedElapsed = 1001))
        assertEquals(1.5, valid.current(1000, "fused", inputStartedElapsed = 1000))
        assertEquals(null, valid.copy(acquiredElapsedMillis = null).current(1000, "fused"))
        assertEquals(null, valid.copy(provider = "").current(1000, ""))
        for (speed in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(null, valid.copy(metersPerSecond = speed).current(1000, "fused"))
        }
        assertEquals(0.0, valid.copy(metersPerSecond = 0.0).current(1000, "fused"))
    }

    @Test
    fun observationCapReusesFasterIncumbentCadenceAndNeverChangesConstant() {
        for (speed in listOf(null, 0.0, low, (low + high) / 2, high)) {
            val selected = interval(speed)
            assertEquals(minOf(60, selected, config.onFootInterval, config.vehicleInterval), ReportingStrategy.observationIntervalSecs(config, selected))
            assertEquals(90, ReportingStrategy.observationIntervalSecs(config.copy(dynamicReporting = false), 90))
            assertEquals(5, ReportingStrategy.observationIntervalSecs(config, 5))
        }
        val saved = config.copy(stationaryInterval = 3600, onFootInterval = 30, vehicleInterval = 60)
        assertEquals(30, ReportingStrategy.observationIntervalSecs(saved, interval(0.0, saved)))
        assertEquals(30, ReportingStrategy.observationIntervalSecs(saved, interval(low, saved)))
        assertEquals(30, ReportingStrategy.observationIntervalSecs(saved, interval(high, saved)))
        assertEquals(3600, interval(0.0, saved))
        assertEquals(5, ReportingStrategy.observationIntervalSecs(saved, interval(0.0, saved, alert = true)))
    }

    @Test
    fun validityExpiresAfterExpectedObservationPlusNativeTimeoutWithoutRejuvenatingOldSamples() {
        val deadline = ReportingStrategy.speedValidUntil(1000, 60, 60_965L, 10_000)
        assertEquals(70_965L, deadline)
        val speed = ReportingSpeed(1.5, 1000, "fused", deadline)
        assertEquals(1.5, speed.current(70_965, "fused"))
        assertEquals(null, speed.current(70_966, "fused"))
        assertEquals(71_000L, ReportingStrategy.speedValidUntil(1000, 60, null, 10_000))
        assertEquals(71_000L, ReportingStrategy.speedValidUntil(1000, 60, 120_965, 10_000))
        // A manual fix between observations does not postpone the already scheduled sample deadline.
        assertEquals(70_965L, ReportingStrategy.speedValidUntil(30_000, 60, 60_965L, 10_000))
    }
}
