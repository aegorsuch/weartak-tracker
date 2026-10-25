package com.tak.weartak_tracker.diagnostics

import com.tak.weartak_tracker.data.DittoState
import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.LocationAccess
import com.tak.weartak_tracker.data.SitxState
import com.tak.weartak_tracker.data.TakStatus
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.service.DebugStayAwakeService
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Observes existing in-memory state only; never starts providers, timers, locks, or network traffic. */
internal suspend fun observeDiagnostics() = coroutineScope {
    launch {
        TrackerState.serviceRunning.collect {
            Diagnostics.record(DiagnosticEvent.TRACKING, if (it) DiagnosticDetail.STARTED else DiagnosticDetail.STOPPED)
        }
    }
    launch {
        TrackerState.locationAccess.collect {
            Diagnostics.record(DiagnosticEvent.LOCATION, when (it) {
                LocationAccess.PRECISE -> DiagnosticDetail.PRECISE
                LocationAccess.APPROXIMATE -> DiagnosticDetail.APPROXIMATE
                LocationAccess.NONE -> DiagnosticDetail.UNAVAILABLE
            })
        }
    }
    launch {
        TrackerState.lastFix.drop(1).collect { if (it != null) Diagnostics.record(DiagnosticEvent.LOCATION, DiagnosticDetail.FIX) }
    }
    launch {
        TrackerState.reportingIntervalSecs.collect { Diagnostics.record(DiagnosticEvent.REPORTING, DiagnosticDetail.CHANGED) }
    }
    launch {
        TrackerState.lastPliMillis.drop(1).collect { if (it > 0) Diagnostics.record(DiagnosticEvent.PLI, DiagnosticDetail.SENT) }
    }
    launch {
        TrackerState.heartRate.map { it > 0 }.distinctUntilChanged().collect {
            Diagnostics.record(DiagnosticEvent.SENSOR, if (it) DiagnosticDetail.AVAILABLE else DiagnosticDetail.UNAVAILABLE)
        }
    }
    launch {
        DebugStayAwakeService.active.collect {
            Diagnostics.record(DiagnosticEvent.WAKELOCK, if (it) DiagnosticDetail.STARTED else DiagnosticDetail.STOPPED)
        }
    }
    for ((endpoint, event) in listOf(
        Endpoint.TAK_SERVER to DiagnosticEvent.TAK, Endpoint.SITX to DiagnosticEvent.SITX,
        Endpoint.MULTICAST to DiagnosticEvent.MULTICAST, Endpoint.DITTO to DiagnosticEvent.DITTO,
    )) launch {
        TrackerState.endpoints.map { endpoint in it }.distinctUntilChanged().collect {
            Diagnostics.record(event, if (it) DiagnosticDetail.CONNECTED else DiagnosticDetail.DISCONNECTED)
        }
    }
    launch {
        TrackerState.takServers.map { it.values.any { state -> state.status == TakStatus.FAILED } }
            .distinctUntilChanged().collect { if (it) Diagnostics.record(DiagnosticEvent.TAK, DiagnosticDetail.FAILED) }
    }
    launch {
        TrackerState.sitx.map { it is SitxState.Error }.distinctUntilChanged()
            .collect { if (it) Diagnostics.record(DiagnosticEvent.SITX, DiagnosticDetail.FAILED) }
    }
    launch {
        TrackerState.ditto.map { it is DittoState.Error }.distinctUntilChanged()
            .collect { if (it) Diagnostics.record(DiagnosticEvent.DITTO, DiagnosticDetail.FAILED) }
    }
}
