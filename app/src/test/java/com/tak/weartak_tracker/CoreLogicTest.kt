package com.tak.weartak_tracker

import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.data.Activity
import com.tak.weartak_tracker.data.ManualAlert
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.service.AlertForwarder
import com.tak.weartak_tracker.service.ReportingStrategy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    private val config = TrackerConfig(
        constantInterval = 30, alertingInterval = 5, onFootInterval = 20, vehicleInterval = 40, stationaryInterval = 600,
    )

    @Test
    fun reportingStrategy() {
        assertEquals(30, ReportingStrategy.intervalSecs(config.copy(dynamicReporting = false), true, Activity.STILL))
        assertEquals(5, ReportingStrategy.intervalSecs(config, true, Activity.STILL))
        assertEquals(600, ReportingStrategy.intervalSecs(config, false, Activity.STILL))
        assertEquals(20, ReportingStrategy.intervalSecs(config, false, Activity.WALKING))
        assertEquals(40, ReportingStrategy.intervalSecs(config, false, Activity.IN_VEHICLE))
        assertEquals(40, ReportingStrategy.intervalSecs(config, false, Activity.UNKNOWN))
    }

    @Test
    fun cotEscapesAndTypes() {
        val time = CotTime.now(30, 0)
        val pli = CotBuilder.pli("u1", "A<&>", "Cyan", "HQ", 50, null, time)
        assertTrue(pli.contains("callsign='A&lt;&amp;&gt;'"))
        assertTrue(pli.contains("type='a-f-G-U-C'"))
        val cancel = CotBuilder.emergency("a1", AlertState.CANCEL, "Manual Alert", "x", 1, "u1", "cs", null, time)
        assertTrue(cancel.contains("type='b-a-o-can'"))
    }

    @Test
    fun storeAndForward() = runBlocking {
        var online = false
        val sent = mutableListOf<ManualAlert>()
        val forwarder = AlertForwarder(send = { if (online) { sent += it; true } else false }, onChanged = { _, _ -> })
        val alert = forwarder.newAlert("Injury")
        assertFalse(forwarder.submit(alert, ready = true))
        assertTrue(forwarder.alerts.single().enqueued)
        val cancel = forwarder.cancelForLast()!!
        forwarder.submit(cancel, ready = true)
        assertEquals(2, forwarder.pending.size)

        online = true
        forwarder.flush()
        assertEquals(listOf(AlertState.ALERT, AlertState.CANCEL), sent.map { it.state })
        assertTrue(forwarder.alerts.isEmpty())
        assertTrue(forwarder.pending.isEmpty())
    }
}
