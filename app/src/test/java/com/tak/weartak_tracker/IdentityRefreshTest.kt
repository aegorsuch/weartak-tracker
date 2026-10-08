package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.service.identityChangedFrom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityRefreshTest {
    private val original = TrackerConfig(callsign = "Tracker", team = "Cyan", role = "Team Member")

    @Test
    fun eachIdentityChangeTriggersRefresh() {
        assertTrue(original.copy(callsign = "New Callsign").identityChangedFrom(original))
        assertTrue(original.copy(team = "Red").identityChangedFrom(original))
        assertTrue(original.copy(role = "HQ").identityChangedFrom(original))
    }

    @Test
    fun initialConfigAndUnchangedIdentityDoNotTriggerRefresh() {
        assertFalse(original.identityChangedFrom(null))
        assertFalse(original.identityChangedFrom(original))
        assertFalse(original.copy(developerMode = true).identityChangedFrom(original))
        assertFalse(original.copy(physioMonitoring = true).identityChangedFrom(original))
        assertFalse(original.copy(constantInterval = original.constantInterval + 1).identityChangedFrom(original))
    }
}
