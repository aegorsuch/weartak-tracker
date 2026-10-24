package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.MoppStatus
import com.tak.weartak_tracker.data.TrackerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MoppStatusTest {
    @Test
    fun unsetIsDistinctFromZeroAndOptionsMatchManualPpeLevels() {
        assertEquals(MoppStatus.NONE, TrackerConfig().moppStatus)
        assertNotEquals(MoppStatus.NONE, MoppStatus.MOPP_0)
        assertEquals(listOf("None", "MOPP 0", "MOPP 1", "MOPP 2", "MOPP 3", "MOPP 4"), MoppStatus.entries.map { it.label })
    }

    @Test
    fun everySelectionRoundTripsAndMissingOrUnknownStorageIsUnset() {
        MoppStatus.entries.forEach { assertEquals(it, MoppStatus.fromStored(it.name)) }
        listOf(null, "", "-1", "0", "MOPP_5", "garbage").forEach {
            assertEquals(MoppStatus.NONE, MoppStatus.fromStored(it))
        }
    }

    @Test
    fun selectingOrClearingPpePreservesOtherSettings() {
        val original = TrackerConfig(callsign = "TEST", physioMonitoring = true, dittoEnabled = true, constantInterval = 30)
        val selected = original.copy(moppStatus = MoppStatus.MOPP_4)
        assertEquals(original, selected.copy(moppStatus = MoppStatus.NONE))
        assertEquals(MoppStatus.MOPP_4, selected.moppStatus)
    }
}
