package com.tak.weartak_tracker

import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.cot.Physio
import com.tak.weartak_tracker.data.Activity
import com.tak.weartak_tracker.data.ManualAlert
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.service.AlertForwarder
import com.tak.weartak_tracker.service.ReportingStrategy
import com.tak.weartak_tracker.ui.MANUAL_ALERT_OPTIONS
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
        assertEquals(20, ReportingStrategy.intervalSecs(config, false, Activity.ON_FOOT))
        assertEquals(40, ReportingStrategy.intervalSecs(config, false, Activity.IN_VEHICLE))
        assertEquals(40, ReportingStrategy.intervalSecs(config, false, Activity.UNKNOWN))
    }

    @Test
    fun pliExpiresAfterTwiceActiveIntervalPlusFifteenSeconds() {
        val modes = listOf(
            Triple(config.copy(dynamicReporting = false), false, Activity.STILL),
            Triple(config, true, Activity.STILL),
            Triple(config, false, Activity.STILL),
            Triple(config, false, Activity.ON_FOOT),
            Triple(config, false, Activity.IN_VEHICLE),
            Triple(config, false, Activity.UNKNOWN),
        )
        modes.forEach { (c, alerting, activity) ->
            val interval = ReportingStrategy.intervalSecs(c, alerting, activity)
            val time = CotTime.now(CotBuilder.pliStaleSeconds(interval), 0)
            val staleMillis = java.time.Instant.parse(time.staleCot).toEpochMilli()
            assertEquals((2L * interval + 15L) * 1000L, staleMillis)
            val pli = CotBuilder.pli("u1", "cs", "Cyan", "HQ", 50, null, time)
            assertTrue(pli.contains("stale='${time.staleCot}'"))
        }
        assertEquals(135L, CotBuilder.pliStaleSeconds(60))
        assertEquals(7215L, CotBuilder.pliStaleSeconds(3600))
        assertEquals(17L, CotBuilder.pliStaleSeconds(0))
        assertEquals(2L * Int.MAX_VALUE + 15, CotBuilder.pliStaleSeconds(Int.MAX_VALUE))
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
    fun pliPhysioMatchesCivAndIsOmittedWhenOff() {
        val time = CotTime.now(30, 0)
        assertFalse(CotBuilder.pli("u1", "cs", "Cyan", "HQ", 50, null, time).contains("biometrics"))
        val on = CotBuilder.pli("u1", "cs", "Cyan", "HQ", 50, null, time, Physio(72))
        assertTrue(on.contains("<remarks>Exert:N/A%;HR:72;SkinTemp:N/A</remarks>"))
        assertTrue(
            on.contains(
                "<biometrics><device><model>WEAROS</model><uid>u1</uid><hr>72</hr><skt>N/A</skt><exert>N/A</exert></device></biometrics>",
            ),
        )
        // Physio detail follows <status> and precedes <contact>, as in CIV.
        assertTrue(on.indexOf("<status") < on.indexOf("<remarks>") && on.indexOf("</biometrics>") < on.indexOf("<contact"))
        val noReading = CotBuilder.pli("u1", "cs", "Cyan", "HQ", 50, null, time, Physio(-1))
        assertTrue(noReading.contains("HR:N/A;") && noReading.contains("<hr>N/A</hr>"))
        val withSkin = CotBuilder.pli("u1", "cs", "Cyan", "HQ", 50, null, time, Physio(64, 91.26f))
        assertTrue(withSkin.contains("<remarks>Exert:N/A%;HR:64;SkinTemp:91.3</remarks>"))
        assertTrue(withSkin.contains("<skt>91.3</skt>"))
    }

    @Test
    fun manualAlertPickerIncludesAllTypesInAlphabeticalOrder() {
        assertEquals(
            listOf(
                "911 Alert", "Gate Runner", "Geofence Breached", "Gunshot", "Gunshot Injury",
                "In Contact", "Injury", "Ring The Bell", "UAS", "Vehicle",
            ),
            MANUAL_ALERT_OPTIONS,
        )
    }

    @Test
    fun addedManualAlertTypesSendWithTheirSelectedDescriptions() = runBlocking {
        val sent = mutableListOf<ManualAlert>()
        val forwarder = AlertForwarder(send = { sent += it; true }, onChanged = { _, _ -> })
        val descriptions = listOf("911 Alert", "Ring The Bell", "Geofence Breached", "In Contact")
        descriptions.forEach { description ->
            val alert = forwarder.newAlert(description)
            assertTrue(forwarder.submit(alert, ready = true))
            val xml = CotBuilder.emergency(
                alert.uid, alert.state, alert.category, alert.description, alert.priority,
                "u1", "cs", null, CotTime.now(CotBuilder.ALERT_STALE_SECONDS, 0),
            )
            assertTrue(xml.contains("alertDescription='$description'"))
            assertTrue(xml.contains("<emergency type='$description'>cs</emergency>"))
        }
        assertEquals(descriptions, sent.map { it.description })
        assertTrue(forwarder.pending.isEmpty())
    }

    @Test
    fun connectedAlertsAndCancelsSendImmediately() = runBlocking {
        val sent = mutableListOf<ManualAlert>()
        val forwarder = AlertForwarder(send = { sent += it; true }, onChanged = { _, _ -> })
        val alert = forwarder.newAlert("Injury")
        assertTrue(forwarder.submit(alert, ready = true))
        assertFalse(forwarder.alerts.single().enqueued)
        assertTrue(forwarder.pending.isEmpty())
        assertTrue(forwarder.submit(forwarder.cancelForLast()!!, ready = true))
        assertEquals(listOf(AlertState.ALERT, AlertState.CANCEL), sent.map { it.state })
        assertTrue(forwarder.alerts.isEmpty())
        assertTrue(forwarder.pending.isEmpty())
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
