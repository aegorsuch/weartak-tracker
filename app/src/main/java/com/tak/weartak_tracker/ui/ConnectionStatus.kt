package com.tak.weartak_tracker.ui

import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.SitxState
import com.tak.weartak_tracker.data.TakServerState
import com.tak.weartak_tracker.data.TakStatus
import com.tak.weartak_tracker.data.TrackerConfig

internal fun connectionStatusIcon(
    config: TrackerConfig,
    states: Map<String, TakServerState>,
    endpoints: Set<Endpoint>,
    sitx: SitxState,
): Int {
    val servers = config.servers.filter { it.enabled && it.address.isNotBlank() }
        .distinctBy { it.endpointKey }
    val statuses = servers.map { states[it.id]?.status ?: TakStatus.DISCONNECTED }
    val connected = statuses.count { it == TakStatus.CONNECTED }
    val connecting = statuses.any { it == TakStatus.CONNECTING || it == TakStatus.ENROLLING } ||
        sitx is SitxState.AwaitingUser || sitx is SitxState.Connecting || sitx is SitxState.Authorized
    return when {
        connected > 0 && connected < servers.size -> R.drawable.tak_server_some_connected
        connected > 0 || Endpoint.SITX in endpoints -> R.drawable.tak_server_connected
        connecting -> R.drawable.tak_server_connecting
        Endpoint.MULTICAST in endpoints -> R.drawable.multicast_connected
        else -> R.drawable.tak_server_disconnected
    }
}
