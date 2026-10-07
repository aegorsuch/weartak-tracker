package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.ui.DeveloperModeTaps
import com.tak.weartak_tracker.ui.isNetworkSettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperSettingsTest {
    @Test
    fun developerModeAndNetworkLockDefaultOff() {
        assertFalse(TrackerConfig().developerMode)
        assertFalse(TrackerConfig().networkPreferencesLocked)
        assertTrue(TrackerConfig(networkPreferencesLocked = true).copy(developerMode = false).networkPreferencesLocked)
    }

    @Test
    fun eightTapsToggleAndResetGesture() {
        val taps = DeveloperModeTaps()
        repeat(8) { assertEquals(7 - it, taps.tap(10_000L + it * 100)) }
        assertEquals(7, taps.tap(10_800))
    }

    @Test
    fun longGapResetsTapCount() {
        val taps = DeveloperModeTaps()
        assertEquals(7, taps.tap(10_000))
        assertEquals(6, taps.tap(11_500))
        assertEquals(7, taps.tap(13_001))
    }

    @Test
    fun lockCoversNetworkRoutesButNotUnlockOrDeviceSettings() {
        listOf(
            "network_preferences", "tak_servers", "new_server_screen", "edit_server_screen/server",
            "tak_channels", "tak_channels/server", "tak_sa_multicast", "multicast_address",
            "multicast_protocol", "multicast_port", "sitx_tak_screen", "sitx_tak_url_screen",
            "sitx_tak_group_screen", "sitx_status_authorization_screen",
        ).forEach { assertTrue(it, isNetworkSettingsRoute(it)) }
        listOf("main_screen", "settings_screen", "callsign_and_device_preferences", "reporting_strategy", "beta_features", "debug_tools")
            .forEach { assertFalse(it, isNetworkSettingsRoute(it)) }
    }
}
