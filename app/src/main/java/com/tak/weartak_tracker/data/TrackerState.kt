package com.tak.weartak_tracker.data

import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.cot.Fix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Endpoint { TAK_SERVER, SITX, MULTICAST }

enum class TakStatus { DISABLED, DISCONNECTED, ENROLLING, CONNECTING, CONNECTED, FAILED, DUPLICATE }

data class TakServerState(val status: TakStatus, val message: String? = null)

sealed interface SitxState {
    data object Disabled : SitxState
    data object Idle : SitxState
    data class AwaitingUser(val userCode: String, val verificationUri: String) : SitxState
    data object Authorized : SitxState
    data object NoGroups : SitxState
    data object NeedsGroup : SitxState
    data object Connecting : SitxState
    data class Connected(val group: String) : SitxState
    data class Error(val message: String) : SitxState
}

data class SitxGroup(val flowTag: String, val name: String)

/** Movement state used by dynamic reporting; UNKNOWN means moving or not yet classified. */
enum class Activity { STILL, ON_FOOT, IN_VEHICLE, UNKNOWN }

/** Manual (911) alert as shown on the watch; `enqueued` means it is waiting in the store-and-forward queue. */
data class ManualAlert(
    val uid: String,
    val state: AlertState,
    val category: String,
    val description: String,
    val priority: Int,
    val timeMillis: Long,
    val enqueued: Boolean,
)

/** Process-wide runtime state shared between the service and UI. */
object TrackerState {
    private val _endpoints = MutableStateFlow<Set<Endpoint>>(emptySet())
    val endpoints: StateFlow<Set<Endpoint>> = _endpoints.asStateFlow()

    fun setEndpoint(endpoint: Endpoint, connected: Boolean) = _endpoints.update {
        if (connected) it + endpoint else it - endpoint
    }

    val takServers = MutableStateFlow<Map<String, TakServerState>>(emptyMap())
    /** Channel lists keyed by TAK Server id; only servers that have connected appear here. */
    val takChannels = MutableStateFlow<Map<String, TakServerChannels>>(emptyMap())
    val sitx = MutableStateFlow<SitxState>(SitxState.Disabled)
    val sitxAccount = MutableStateFlow<SitxAccount?>(null)
    val sitxAuthorized = MutableStateFlow(false)
    val sitxRenewalMessage = MutableStateFlow("")
    val sitxAuthorizationPromptOpen = MutableStateFlow(false)
    val sitxGroups = MutableStateFlow<List<SitxGroup>>(emptyList())
    val lastFix = MutableStateFlow<Fix?>(null)
    val lastPliMillis = MutableStateFlow(0L)
    val reportingIntervalSecs = MutableStateFlow(DEFAULT_CONSTANT_REPORTING_INTERVAL)
    val activity = MutableStateFlow(Activity.UNKNOWN)
    val locationAccess = MutableStateFlow(LocationAccess.NONE)
    /** Latest heart rate for physio PLI; -1 when unavailable or physio monitoring is off. */
    val heartRate = MutableStateFlow(-1)
    /** Latest skin temperature in degrees F (Samsung watches only); null when unavailable or physio is off. */
    val skinTempF = MutableStateFlow<Float?>(null)
    val alerts = MutableStateFlow<List<ManualAlert>>(emptyList())
    val serviceRunning = MutableStateFlow(false)

    val isAlerting: Boolean get() = alerts.value.any { it.state == AlertState.ALERT }
}
