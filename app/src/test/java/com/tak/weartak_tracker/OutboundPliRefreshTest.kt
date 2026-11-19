package com.tak.weartak_tracker

import com.tak.weartak_tracker.cot.Fix
import com.tak.weartak_tracker.service.OutboundPliRefresh
import com.tak.weartak_tracker.service.isCurrentPliFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundPliRefreshTest {
    @Test
    fun actualAcquisitionClockAndExistingThreeSecondBoundaryDetermineFreshness() {
        val fix = Fix(1.0, 2.0, 0.0, 10f, 10f, 0f, 0f, 123L, 10_000L)
        assertTrue(fix.isCurrentPliFix(13_000, 3_000))
        assertFalse(fix.isCurrentPliFix(13_001, 3_000))
        assertFalse(fix.isCurrentPliFix(9_999, 3_000))
        assertFalse(fix.copy(elapsedRealtimeMillis = null).isCurrentPliFix(10_001, 3_000))
        assertFalse(fix.copy(elapsedRealtimeMillis = 0).isCurrentPliFix(1, 3_000))
        assertFalse(fix.copy(lat = Double.NaN).isCurrentPliFix(10_001, 3_000))
        assertFalse(fix.copy(lon = 181.0).isCurrentPliFix(10_001, 3_000))
        assertFalse(fix.copy(ce = Float.NaN).isCurrentPliFix(10_001, 3_000))
        assertFalse(fix.copy(ce = -1f).isCurrentPliFix(10_001, 3_000))
        // A new wall-clock send/callback time cannot rejuvenate an old acquisition.
        assertFalse(fix.copy(timeMillis = Long.MAX_VALUE).isCurrentPliFix(20_000, 3_000))
    }

    @Test
    fun pendingSurvivesNoFixOrSendFailureAndUnchangedReadyDoesNotRepeat() {
        val refresh = OutboundPliRefresh()
        val ready = mapOf("ditto" to 1L)
        refresh.update(ready)
        assertTrue(refresh.needed)
        assertTrue(refresh.takeFixRequest())
        repeat(5) { refresh.update(ready) }
        assertFalse(refresh.takeFixRequest())
        refresh.onPublished(emptyMap())
        assertEquals(ready, refresh.snapshot())
        refresh.onPublished(ready)
        assertFalse(refresh.needed)
        refresh.update(ready)
        assertFalse(refresh.needed)
    }

    @Test
    fun reconnectSecondServerAndReplacedDestinationAreSeparateGenerations() {
        val refresh = OutboundPliRefresh()
        val original = mapOf("tak:a" to 1L, "multicast" to 1L)
        refresh.update(original)
        refresh.onPublished(original)
        val next = mapOf("tak:a" to 1L, "tak:b" to 2L, "multicast" to 2L)
        refresh.update(next)
        assertEquals(mapOf("tak:b" to 2L, "multicast" to 2L), refresh.snapshot())
        refresh.onPublished(refresh.snapshot())
        refresh.update(next + ("tak:a" to 3L))
        assertEquals(mapOf("tak:a" to 3L), refresh.snapshot())
    }

    @Test
    fun removedOrDisallowedRouteCancelsItsPendingAndOldSendCannotClearReplacement() {
        val refresh = OutboundPliRefresh()
        refresh.update(mapOf("ditto" to 1L, "sitx" to 1L))
        val inFlight = refresh.snapshot()
        refresh.update(mapOf("ditto" to 2L))
        refresh.onPublished(inFlight)
        assertEquals(mapOf("ditto" to 2L), refresh.snapshot())
        assertTrue(refresh.takeFixRequest())
        refresh.update(emptyMap())
        assertFalse(refresh.needed)
        refresh.update(mapOf("ditto" to 3L))
        assertEquals(mapOf("ditto" to 3L), refresh.snapshot())
    }
}
