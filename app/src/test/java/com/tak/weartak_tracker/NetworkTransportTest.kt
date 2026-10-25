package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.DittoPdlSource
import com.tak.weartak_tracker.data.DittoState
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TakTransportPreferences
import com.tak.weartak_tracker.data.runtimeTakServers
import com.tak.weartak_tracker.data.TakServerState
import com.tak.weartak_tracker.data.TakStatus
import com.tak.weartak_tracker.data.SitxState
import com.tak.weartak_tracker.ui.connectionStatusIcon
import com.tak.weartak_tracker.transport.TakChannelApi
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.IOException
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import com.tak.weartak_tracker.ui.NetworkTransport
import com.tak.weartak_tracker.ui.dittoEnableBlocker
import com.tak.weartak_tracker.ui.dittoStatusResource
import com.tak.weartak_tracker.ui.networkTransports
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NetworkTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val saved = TrackerConfig(
        servers = listOf(TakServerConfig(id = "one", name = "Saved", address = "example.invalid", enabled = true)),
        multicastAddress = "239.1.2.3",
        sitxUrl = "https://example.invalid", sitxClientId = "client", sitxGroup = "group",
        dittoPdlSource = DittoPdlSource.CUSTOM, dittoCustomPdl = "private-identity",
    )

    @Test
    fun checklistUsesSavedEnableStateAndRetainsEveryOtherPreferenceAndCredential() {
        for (transport in NetworkTransport.entries) {
            val enabled = transport.setEnabled(saved, true)
            assertTrue(transport.enabled(enabled))
            val disabled = transport.setEnabled(enabled, false)
            assertFalse(transport.enabled(disabled))
            assertEquals(enabled, transport.setEnabled(disabled, true))
            assertEquals(saved.servers, disabled.servers)
            assertEquals(saved.dittoCustomPdl, disabled.dittoCustomPdl)
            assertEquals(saved.dittoPdlSource, disabled.dittoPdlSource)
            assertEquals(saved.sitxUrl, disabled.sitxUrl)
            assertEquals(saved.sitxClientId, disabled.sitxClientId)
            assertEquals(saved.sitxGroup, disabled.sitxGroup)
            assertEquals(saved.multicastAddress, disabled.multicastAddress)
        }
    }

    @Test
    fun liveNetworkLockRejectsOnAndOffWithoutMutatingState() {
        for (transport in NetworkTransport.entries) {
            val locked = saved.copy(networkPreferencesLocked = true)
            assertSame(locked, transport.setEnabled(locked, true))
            assertSame(locked, transport.setEnabled(locked, false))
        }
    }

    @Test
    fun delayedPermissionCannotEnableAfterLockOrIdentityReplacementOrRevocation() {
        assertNull(dittoEnableBlocker(saved, saved, true))
        assertEquals(R.string.network_settings_locked, dittoEnableBlocker(saved.copy(networkPreferencesLocked = true), saved, true))
        assertEquals(R.string.transport_identity_changed, dittoEnableBlocker(saved.copy(dittoCustomPdl = "replacement"), saved, true))
        assertEquals(R.string.transport_identity_changed, dittoEnableBlocker(saved.copy(dittoPdlSource = DittoPdlSource.COMMUNITY), saved, true))
        assertEquals(R.string.transport_nearby_required, dittoEnableBlocker(saved, saved, false))
        assertNull(dittoEnableBlocker(saved.copy(callsign = "changed"), saved, true))
    }

    @Test
    fun dittoSubtitleUsesOnlyActualStatusResourceNeverRawErrorOrIdentity() {
        assertEquals(R.string.transport_disabled, dittoStatusResource(DittoState.Disabled))
        assertEquals(R.string.transport_needs_identity, dittoStatusResource(DittoState.NeedsIdentity))
        assertEquals(R.string.transport_starting, dittoStatusResource(DittoState.Starting))
        assertEquals(R.string.transport_sync_active, dittoStatusResource(DittoState.Active))
        assertEquals(R.string.transport_error, dittoStatusResource(DittoState.Error("private-identity credentials")))
    }

    @Test
    fun checklistSortsLocalizedVisibleLabelsAndHasOnlyImplementedTransports() {
        val labels = mapOf(
            NetworkTransport.DITTO to "Zustand",
            NetworkTransport.MULTICAST to "Betrieb",
            NetworkTransport.SITX to "Änderung",
            NetworkTransport.TAK_SERVERS to "Caractéristiques",
        )
        assertEquals(
            listOf(NetworkTransport.SITX, NetworkTransport.MULTICAST, NetworkTransport.TAK_SERVERS, NetworkTransport.DITTO),
            networkTransports(labels, Locale.GERMAN),
        )
        assertEquals(4, NetworkTransport.entries.size)
        assertEquals(4, NetworkTransport.entries.map { it.labelRes }.distinct().size)
    }

    @Test
    fun takMasterMasksRuntimeOnlyAndRestoresExactIndividualChoicesIncludingEmptyAndDisabledLists() {
        val chosen = saved.copy(servers = listOf(
            saved.servers.single().copy(username = "user", password = "secret"),
            TakServerConfig(id = "two", address = "disabled.invalid", enabled = false),
        ))
        for (servers in listOf(emptyList(), chosen.servers, chosen.servers.map { it.copy(enabled = false) })) {
            val before = chosen.copy(servers = servers)
            assertTrue(before.takServersEnabled)
            assertEquals(servers, before.runtimeTakServers)
            val off = NetworkTransport.TAK_SERVERS.setEnabled(before, false)
            assertEquals(servers, off.servers)
            assertTrue(off.runtimeTakServers.none { it.enabled })
            assertEquals(servers.map { it.copy(enabled = false) }, off.runtimeTakServers)
            assertEquals(before, NetworkTransport.TAK_SERVERS.setEnabled(off, true))
        }
    }

    @Test
    fun defaultOnMasterPersistsOffAndOnAcrossDataStoreRecreation() = runBlocking {
        assertTrue(TakTransportPreferences.enabled(emptyPreferences()))
        val file = File(temporary.root, "network.preferences_pb")
        for (enabled in listOf(false, true, false)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                store.edit { TakTransportPreferences.write(it, enabled) }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reopened = PreferenceDataStoreFactory.create(scope = reopenedScope, produceFile = { file })
                assertEquals(enabled, TakTransportPreferences.enabled(reopened.data.first()))
            } finally {
                reopenedScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun uncheckedTakMasterCannotDisplayStaleConnectedOrConnectingState() {
        val off = saved.copy(takServersEnabled = false)
        for (status in listOf(TakStatus.CONNECTED, TakStatus.CONNECTING, TakStatus.ENROLLING)) {
            assertEquals(
                R.drawable.tak_server_disconnected,
                connectionStatusIcon(off, mapOf("one" to TakServerState(status)), emptySet(), SitxState.Disabled),
            )
        }
    }

    @Test
    fun closedChannelClientCannotIssueRefreshOrWriteRequests() {
        val api = TakChannelApi("example.invalid", SSLSocketFactory.getDefault() as SSLSocketFactory)
        api.close()
        api.close()
        for (request in listOf<() -> Unit>({ api.load(true) }, { api.load(false) }, { api.setActive(1, true, "uid") })) {
            val error = runCatching(request).exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals("TAK Server is disabled or disconnected", error?.message)
        }
    }
}
