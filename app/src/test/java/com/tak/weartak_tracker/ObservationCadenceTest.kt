package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.Activity
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.service.MovementClassifier
import com.tak.weartak_tracker.service.ObservationCadence
import com.tak.weartak_tracker.service.ReportingSpeed
import com.tak.weartak_tracker.service.ReportingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationCadenceTest {
    private val config = TrackerConfig()

    private class Runtime(var motionAvailable: Boolean = true) {
        val events = mutableListOf<String>()
        var armed = false
        val cadence = ObservationCadence(
            armMotion = {
                armed = motionAvailable
                events += "arm:$armed"
                armed
            },
            disarmMotion = { armed = false; events += "disarm" },
            schedule = { events += "schedule:$it" },
            requestFix = { events += "fix" },
            stopLocation = { events += "stop" },
        )
    }

    @Test
    fun guardedStationaryUsesHourThenMotionReplacesAlarmAndRequestsFreshFixImmediately() {
        val runtime = Runtime()
        val movement = MovementClassifier()
        runtime.cadence.update(config, movement.onSpeed(0f), false)
        assertEquals(listOf("arm:true", "schedule:3600"), runtime.events)
        runtime.events.clear()
        runtime.cadence.update(config, movement.onMotion(), false, freshFix = true)
        assertEquals(listOf("disarm", "schedule:60", "fix"), runtime.events)
        assertFalse(runtime.armed)
        runtime.events.clear()
        runtime.cadence.update(config, movement.onSpeed(2f), false)
        assertEquals(listOf("disarm"), runtime.events)
    }

    @Test
    fun missingOrFailedMotionRegistrationCannotStrandStationaryLocation() {
        val runtime = Runtime(motionAvailable = false)
        runtime.cadence.update(config, Activity.STILL, false)
        assertEquals(listOf("arm:false", "schedule:60"), runtime.events)
        runtime.motionAvailable = true
        runtime.events.clear()
        runtime.cadence.update(config, Activity.STILL, false)
        assertEquals(listOf("arm:true", "schedule:3600"), runtime.events)
        runtime.motionAvailable = false
        runtime.events.clear()
        runtime.cadence.update(config, Activity.STILL, false)
        assertEquals(listOf("arm:false", "schedule:60", "fix"), runtime.events)
    }

    @Test
    fun stationaryToUnknownOrUnusableSpeedSchedulesPromptObservation() {
        for (speed in listOf(null, Double.NaN, -1.0, 3.0)) {
            val runtime = Runtime()
            runtime.cadence.update(config, Activity.STILL, false)
            runtime.events.clear()
            runtime.cadence.update(config, ReportingStrategy.observationActivity(Activity.STILL, speed), false)
            assertEquals(listOf("disarm", "schedule:60", "fix"), runtime.events)
        }
    }

    @Test
    fun expiredStationarySpeedCannotBeRejuvenatedByRepeatedPolicyUpdates() {
        val runtime = Runtime()
        val deadline = ReportingStrategy.speedValidUntil(1000, 3600, 3_600_965, 10_000)
        val speed = ReportingSpeed(0.0, 1000, "fused", deadline)
        runtime.cadence.update(config,
            ReportingStrategy.observationActivity(Activity.STILL, speed.current(deadline, "fused")), false)
        assertTrue(runtime.armed)
        runtime.events.clear()
        runtime.cadence.update(config,
            ReportingStrategy.observationActivity(Activity.STILL, speed.current(deadline + 1, "fused")), false)
        assertEquals(listOf("disarm", "schedule:60", "fix"), runtime.events)
        assertEquals(null, speed.current(deadline + 1, "fused"))
    }

    @Test
    fun alertActivationAndCancellationUseSavedProfilesWithoutRetainingStationaryAlarm() {
        val runtime = Runtime()
        runtime.cadence.update(config, Activity.STILL, false)
        runtime.events.clear()
        runtime.cadence.update(config, Activity.STILL, true)
        assertEquals(listOf("disarm", "schedule:10", "fix"), runtime.events)
        runtime.events.clear()
        runtime.cadence.update(config, Activity.STILL, false)
        assertEquals(listOf("arm:true", "schedule:3600"), runtime.events)
        val slower = config.copy(alertingInterval = 300)
        runtime.events.clear()
        runtime.cadence.update(slower, Activity.UNKNOWN, true)
        assertEquals(listOf("disarm", "schedule:300", "fix"), runtime.events)
    }

    @Test
    fun interactionRequestsFreshFixEvenWithoutChangingIntervalAndConstantModeStaysExact() {
        for (saved in listOf(config, config.copy(dynamicReporting = false, constantInterval = 7200))) {
            val runtime = Runtime()
            runtime.cadence.update(saved, Activity.STILL, false)
            runtime.events.clear()
            runtime.cadence.update(saved, Activity.STILL, false, freshFix = true)
            assertEquals(listOf(if (saved.dynamicReporting) "arm:true" else "disarm", "fix"), runtime.events)
            runtime.events.clear()
            runtime.cadence.update(saved, Activity.UNKNOWN, true)
            if (!saved.dynamicReporting) assertEquals(listOf("disarm"), runtime.events)
        }
    }

    @Test
    fun changedSettingsReplaceScheduleButUnchangedSettingsDoNotRestartIt() {
        val runtime = Runtime()
        runtime.cadence.update(config, Activity.STILL, false)
        runtime.events.clear()
        runtime.cadence.update(config.copy(stationaryInterval = 1800), Activity.STILL, false)
        assertEquals(listOf("arm:true", "schedule:1800", "fix"), runtime.events)
        runtime.events.clear()
        runtime.cadence.update(config.copy(stationaryInterval = 1800), Activity.STILL, false)
        assertEquals(listOf("arm:true"), runtime.events)
        runtime.events.clear()
        runtime.cadence.update(config.copy(dynamicReporting = false, constantInterval = 900), Activity.STILL, false)
        assertEquals(listOf("disarm", "schedule:900", "fix"), runtime.events)
        runtime.events.clear()
        runtime.cadence.update(config.copy(dynamicReporting = false, constantInterval = 7200), Activity.STILL, false)
        assertEquals(listOf("disarm", "schedule:7200"), runtime.events)
    }

    @Test
    fun movingAndUnknownProfilesNeverClampUserConfiguredSlowerIntervals() {
        val saved = config.copy(onFootInterval = 300, vehicleInterval = 900, alertingInterval = 120)
        val runtime = Runtime()
        runtime.cadence.update(saved, Activity.UNKNOWN, false)
        assertEquals(listOf("disarm", "schedule:900"), runtime.events)
        runtime.events.clear()
        runtime.cadence.update(saved, Activity.ON_FOOT, false)
        assertEquals(listOf("disarm", "schedule:300", "fix"), runtime.events)
        runtime.events.clear()
        runtime.cadence.update(saved, Activity.IN_VEHICLE, false)
        assertEquals(listOf("disarm", "schedule:900"), runtime.events)
    }

    @Test
    fun stopCancelsLocationAndMotionAndLateEventsCannotRestartEither() {
        val runtime = Runtime()
        runtime.cadence.update(config, Activity.STILL, false)
        runtime.events.clear()
        runtime.cadence.stop()
        assertEquals(listOf("disarm", "stop"), runtime.events)
        assertFalse(runtime.armed)
        runtime.events.clear()
        runtime.cadence.update(config, Activity.UNKNOWN, false, freshFix = true)
        runtime.cadence.update(config, Activity.STILL, true)
        runtime.cadence.stop()
        assertTrue(runtime.events.isEmpty())
    }
}
