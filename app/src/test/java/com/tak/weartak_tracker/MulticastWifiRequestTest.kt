package com.tak.weartak_tracker

import com.tak.weartak_tracker.transport.MulticastWifiRequest
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticastWifiRequestTest {
    private class Harness {
        val events = mutableListOf<String>()
        var held = false
        var failAcquire = false
        var failRequest = false
        var failUnregister = false
        val demand = MulticastWifiRequest(
            acquireLock = {
                events += "acquire"
                if (failAcquire) throw SecurityException()
                held = true
            },
            releaseLock = {
                events += "release"
                held = false
            },
            requestNetwork = {
                events += "request"
                if (failRequest) throw SecurityException()
                Closeable {
                    events += "unregister"
                    if (failUnregister) throw IllegalArgumentException()
                }
            },
            onFailure = { events += "failure" },
        )
    }

    @Test
    fun enablingIsIdempotentAndDisablingReleasesBoth() {
        val h = Harness()
        h.demand.setEnabled(false)
        h.demand.setEnabled(true)
        h.demand.setEnabled(true)
        assertTrue(h.held)
        assertEquals(listOf("acquire", "request"), h.events)
        h.demand.setEnabled(false)
        h.demand.close()
        assertFalse(h.held)
        assertEquals(listOf("acquire", "request", "unregister", "release"), h.events)
    }

    @Test
    fun serviceStopReleasesAndCanReenable() {
        val h = Harness()
        h.demand.setEnabled(true)
        h.demand.close()
        assertFalse(h.held)
        h.demand.setEnabled(true)
        assertTrue(h.held)
        assertEquals(listOf("acquire", "request", "unregister", "release", "acquire", "request"), h.events)
        h.demand.close()
    }

    @Test
    fun requestFailureRollsBackLockAndNextEnableRetries() {
        val h = Harness()
        h.failRequest = true
        h.demand.setEnabled(true)
        assertFalse(h.held)
        assertEquals(listOf("acquire", "request", "failure", "release"), h.events)
        h.failRequest = false
        h.demand.setEnabled(true)
        assertTrue(h.held)
        h.demand.close()
        assertEquals(listOf("acquire", "request", "failure", "release",
            "acquire", "request", "unregister", "release"), h.events)
    }

    @Test
    fun acquireFailureDoesNotRequestNetworkAndCanRetry() {
        val h = Harness()
        h.failAcquire = true
        h.demand.setEnabled(true)
        assertFalse(h.held)
        assertEquals(listOf("acquire", "failure", "release"), h.events)
        h.failAcquire = false
        h.demand.setEnabled(true)
        assertTrue(h.held)
        h.demand.close()
    }

    @Test
    fun unregisterFailureStillReleasesLockAndAllowsRetry() {
        val h = Harness()
        h.demand.setEnabled(true)
        h.failUnregister = true
        h.demand.close()
        assertFalse(h.held)
        assertEquals(listOf("acquire", "request", "unregister", "failure", "release"), h.events)
        h.failUnregister = false
        h.demand.setEnabled(true)
        assertTrue(h.held)
        h.demand.close()
    }
}
