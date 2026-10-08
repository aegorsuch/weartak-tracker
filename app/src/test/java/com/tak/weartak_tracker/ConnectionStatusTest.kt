package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.SitxState
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TakServerState
import com.tak.weartak_tracker.data.TakStatus
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.ui.connectionStatusIcon
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStatusTest {
    private val first = TakServerConfig(id = "first", address = "first.example.org")
    private val second = TakServerConfig(id = "second", address = "second.example.org")
    private val config = TrackerConfig(servers = listOf(first, second))

    private fun icon(
        statuses: Map<String, TakStatus>,
        settings: TrackerConfig = config,
        endpoints: Set<Endpoint> = emptySet(),
    ) = connectionStatusIcon(
        settings, statuses.mapValues { TakServerState(it.value) }, endpoints, SitxState.Disabled,
    )

    @Test
    fun mixedConnectionsUseSplitIconEvenWhenSitxIsConnected() {
        for (status in listOf(TakStatus.FAILED, TakStatus.DISCONNECTED, TakStatus.CONNECTING, TakStatus.ENROLLING)) {
            assertEquals(
                R.drawable.tak_server_some_connected,
                icon(mapOf(first.id to TakStatus.CONNECTED, second.id to status), endpoints = setOf(Endpoint.SITX)),
            )
        }
    }

    @Test
    fun missingRuntimeStateCountsAsNotConnected() {
        assertEquals(R.drawable.tak_server_some_connected, icon(mapOf(first.id to TakStatus.CONNECTED)))
    }

    @Test
    fun allConnectedUseGreenIconWithoutWaitingForAggregateEndpoint() {
        assertEquals(
            R.drawable.tak_server_connected,
            icon(mapOf(first.id to TakStatus.CONNECTED, second.id to TakStatus.CONNECTED)),
        )
    }

    @Test
    fun disabledAndDuplicateConnectionsDoNotCountAgainstConnectedServer() {
        val settings = config.copy(servers = listOf(first, second.copy(enabled = false), first.copy(id = "duplicate")))
        assertEquals(R.drawable.tak_server_connected, icon(mapOf(first.id to TakStatus.CONNECTED), settings))
    }

    @Test
    fun noConnectedServersPreserveConnectingMulticastAndDisconnectedIcons() {
        assertEquals(R.drawable.tak_server_connecting, icon(mapOf(first.id to TakStatus.CONNECTING)))
        assertEquals(R.drawable.multicast_connected, icon(emptyMap(), endpoints = setOf(Endpoint.MULTICAST)))
        assertEquals(R.drawable.tak_server_disconnected, icon(emptyMap()))
    }
}
