package com.tak.weartak_tracker

import com.tak.weartak_tracker.service.isTrackingResumeBroadcast
import com.tak.weartak_tracker.service.requestTrackingStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingStartTest {
    @Test
    fun backgroundCreationNeverStartsLocationServiceAndAsksUserToResume() {
        checkStart(location = true, running = false, resumed = false, expected = "resume")
    }

    @Test
    fun notificationTapOrLauncherResumeCanStartTracking() {
        checkStart(location = true, running = false, resumed = true, expected = "start")
    }

    @Test
    fun backgroundActionsCanReachAnAlreadyRunningService() {
        checkStart(location = true, running = true, resumed = false, expected = "start")
    }

    @Test
    fun missingLocationCannotStartEvenWithVisibleUiOrRunningService() {
        for (running in listOf(false, true)) for (resumed in listOf(false, true)) {
            checkStart(location = false, running = running, resumed = resumed, expected = "resume")
        }
    }

    @Test
    fun platformRejectionIsReportedAndUserCanRetryAfterResume() {
        for (error in listOf(IllegalStateException("background FGS denied"), SecurityException("while-in-use denied"))) {
            var starts = 0
            var reported: RuntimeException? = null
            requestTrackingStart(true, false, true, {
                starts++
                throw error
            }, { throw AssertionError("Not a deferred request") }, { reported = it })
            assertSame(error, reported)
            requestTrackingStart(true, false, true, { starts++ }, {
                throw AssertionError("Resume should retry")
            }, { throw AssertionError(it) })
            assertEquals(2, starts)
        }
    }

    @Test
    fun updateAndBootOnlyRequestUserResume() {
        assertTrue(isTrackingResumeBroadcast("android.intent.action.MY_PACKAGE_REPLACED"))
        assertTrue(isTrackingResumeBroadcast("android.intent.action.BOOT_COMPLETED"))
        assertFalse(isTrackingResumeBroadcast(null))
        assertFalse(isTrackingResumeBroadcast("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(isTrackingResumeBroadcast("android.intent.action.PACKAGE_ADDED"))
    }

    private fun checkStart(location: Boolean, running: Boolean, resumed: Boolean, expected: String) {
        val actions = mutableListOf<String>()
        requestTrackingStart(location, running, resumed, { actions.add("start") }, { actions.add("resume") }, {
            throw AssertionError(it)
        })
        assertEquals(listOf(expected), actions)
    }
}
