package com.tak.weartak_tracker

import com.tak.weartak_tracker.ui.PLUGIN_TITLES
import com.tak.weartak_tracker.ui.pluginIconResource
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginIconsTest {
    @Test
    fun everyExistingPluginHasTheExpectedDistinctIcon() {
        val expected = mapOf(
            "Commands" to R.drawable.ic_commands_megaphone,
            "Ditto Edge Sync" to R.drawable.ic_integration_ditto,
            "Everywhere Comms" to R.drawable.ic_integration_everywhere,
            "GoTenna" to R.drawable.ic_integration_gotenna,
            "MOPP" to R.drawable.mopp_mask,
            "Sensor Manager" to R.drawable.ic_plugin_sensor,
            "Somewear Labs" to R.drawable.ic_integration_somewear,
        )
        assertEquals(PLUGIN_TITLES.toSet(), expected.keys)
        expected.forEach { (title, icon) -> assertEquals(icon, pluginIconResource(title)) }
        assertEquals(expected.size, expected.values.toSet().size)
    }
}
