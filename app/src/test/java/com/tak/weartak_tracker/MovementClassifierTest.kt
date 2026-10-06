package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.Activity
import com.tak.weartak_tracker.service.MovementClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class MovementClassifierTest {
    @Test
    fun firstFixReplacesUnknownImmediately() {
        assertEquals(Activity.ON_FOOT, MovementClassifier().onSpeed(1.4f))
        assertEquals(Activity.IN_VEHICLE, MovementClassifier().onSpeed(15f))
        assertEquals(Activity.STILL, MovementClassifier().onSpeed(0.1f))
    }

    @Test
    fun fixesWithoutSpeedAreIgnored() {
        val c = MovementClassifier()
        c.onSpeed(1.4f)
        assertEquals(Activity.ON_FOOT, c.onSpeed(null))
        assertEquals(Activity.ON_FOOT, c.onSpeed(Float.NaN))
        assertEquals(Activity.ON_FOOT, c.onSpeed(-1f))
    }

    @Test
    fun singleNoisyFixDoesNotSwitch() {
        val c = MovementClassifier()
        c.onSpeed(1.4f)
        assertEquals(Activity.ON_FOOT, c.onSpeed(8f))
        assertEquals(Activity.ON_FOOT, c.onSpeed(1.2f))
        assertEquals(Activity.ON_FOOT, c.onSpeed(8f))
        assertEquals(Activity.IN_VEHICLE, c.onSpeed(9f))
    }

    @Test
    fun shortStopKeepsMovingIntervalButLongStopIsStill() {
        val c = MovementClassifier()
        c.onSpeed(15f)
        assertEquals(Activity.IN_VEHICLE, c.onSpeed(0f))
        assertEquals(Activity.IN_VEHICLE, c.onSpeed(0f))
        assertEquals(Activity.IN_VEHICLE, c.onSpeed(12f)) // light turned green
        c.onSpeed(0f)
        c.onSpeed(0f)
        assertEquals(Activity.STILL, c.onSpeed(0f))
    }

    @Test
    fun motionLeavesStillOnlyFromStill() {
        val c = MovementClassifier()
        c.onSpeed(0f)
        assertEquals(Activity.UNKNOWN, c.onMotion())
        // After motion the next real fix decides immediately.
        assertEquals(Activity.ON_FOOT, c.onSpeed(1.5f))
        assertEquals(Activity.ON_FOOT, c.onMotion())
    }

    @Test
    fun thresholdBoundaries() {
        val c = MovementClassifier()
        assertEquals(Activity.ON_FOOT, MovementClassifier().onSpeed(MovementClassifier.STILL_BELOW_MPS))
        assertEquals(Activity.ON_FOOT, MovementClassifier().onSpeed(MovementClassifier.VEHICLE_ABOVE_MPS))
        assertEquals(Activity.UNKNOWN, c.current)
    }
}
