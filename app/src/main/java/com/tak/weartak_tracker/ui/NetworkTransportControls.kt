package com.tak.weartak_tracker.ui

import android.widget.Toast
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.SplitToggleChip
import androidx.wear.compose.material.Text
import com.tak.weartak_tracker.BuildConfig
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.data.DittoPdlSource
import com.tak.weartak_tracker.data.DittoState
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TakServerState
import com.tak.weartak_tracker.data.TakStatus
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.data.sortedByDisplayedLabel
import com.tak.weartak_tracker.diagnostics.AppLog
import com.tak.weartak_tracker.service.DittoCommunityLicenseHelper
import com.tak.weartak_tracker.service.TrackerService
import java.util.Locale

internal enum class NetworkTransport(val labelRes: Int) {
    DITTO(R.string.transport_ditto),
    MULTICAST(R.string.transport_multicast),
    SITX(R.string.transport_sitx),
    TAK_SERVERS(R.string.transport_tak_servers);

    fun enabled(config: TrackerConfig): Boolean = when (this) {
        DITTO -> config.dittoEnabled
        MULTICAST -> config.multicastEnabled
        SITX -> config.sitxEnabled
        TAK_SERVERS -> config.takServersEnabled
    }

    fun setEnabled(config: TrackerConfig, enabled: Boolean): TrackerConfig =
        if (config.networkPreferencesLocked) config else when (this) {
            DITTO -> config.copy(dittoEnabled = enabled)
            MULTICAST -> config.copy(multicastEnabled = enabled)
            SITX -> config.copy(sitxEnabled = enabled)
            TAK_SERVERS -> config.copy(takServersEnabled = enabled)
        }
}

internal fun networkTransports(labels: Map<NetworkTransport, String>, locale: Locale): List<NetworkTransport> =
    sortedByDisplayedLabel(NetworkTransport.entries.toList(), locale) { labels.getValue(it) }

private fun saveNetworkPreference(
    repo: SettingsRepository,
    context: Context,
    transform: (TrackerConfig) -> TrackerConfig,
    onApplied: (TrackerConfig) -> Unit,
) {
    var allowed = false
    var appliedConfig: TrackerConfig? = null
    val main = Handler(Looper.getMainLooper())
    repo.updateAsync(
        onUpdated = {
            main.post {
                if (allowed) onApplied(requireNotNull(appliedConfig))
                else Toast.makeText(context, R.string.network_settings_locked, Toast.LENGTH_LONG).show()
            }
        },
        onError = { error ->
            AppLog.e("NetworkPreferences", "Unable to save network preference", error)
            main.post { Toast.makeText(context, R.string.transport_save_failed, Toast.LENGTH_LONG).show() }
        },
    ) { current ->
        allowed = !current.networkPreferencesLocked
        (if (allowed) transform(current) else current).also { appliedConfig = it }
    }
}

@Composable
internal fun rememberNetworkUpdater(
    repo: SettingsRepository,
    onApplied: (TrackerConfig) -> Unit = {},
): ((TrackerConfig) -> TrackerConfig) -> Unit {
    val context = LocalContext.current.applicationContext
    return { transform ->
        saveNetworkPreference(repo, context, transform, onApplied)
    }
}

@Composable
internal fun networkTransportChecklist(
    repo: SettingsRepository,
    config: State<TrackerConfig>,
    openDittoIdentity: () -> Unit,
    openTransportSettings: (NetworkTransport) -> Unit,
): ScalingLazyListScope.() -> Unit {
    val context = LocalContext.current.applicationContext
    val labels = NetworkTransport.entries.associateWith { stringResource(it.labelRes) }
    val entries = networkTransports(labels, LocalConfiguration.current.locales[0])
    val toggleDitto = rememberDittoToggle(repo, config, openDittoIdentity)
    val takServerStates by TrackerState.takServers.collectAsStateWithLifecycle()
    return {
        entries.forEach { transport ->
            item(key = "transport-${transport.name}") {
                val enabled = transport.enabled(config.value)
                val locked = config.value.networkPreferencesLocked
                val serverSummary = if (transport == NetworkTransport.TAK_SERVERS) {
                    takServerConnectionSummary(config.value, takServerStates)
                } else null
                // Row body opens the transport's settings (still allowed when locked); the checkbox only toggles.
                SplitToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(labels.getValue(transport)) },
                    secondaryLabel = serverSummary?.let { summary -> ({ Text(summary) }) },
                    checked = enabled,
                    toggleControl = {
                        Checkbox(checked = enabled, onCheckedChange = null, enabled = !locked)
                    },
                    onCheckedChange = { selected ->
                        when {
                            locked -> Toast.makeText(context, R.string.network_settings_locked, Toast.LENGTH_LONG).show()
                            transport == NetworkTransport.DITTO -> toggleDitto(selected)
                            else -> saveNetworkPreference(
                                repo, context, { transport.setEnabled(it, selected) },
                            ) { if (selected) TrackerService.start(context) }
                        }
                    },
                    onClick = { openTransportSettings(transport) },
                )
            }
        }
    }
}

internal fun NetworkTransport.settingsRoute(): String = when (this) {
    NetworkTransport.DITTO -> "ditto_identity"
    NetworkTransport.MULTICAST -> "tak_sa_multicast"
    NetworkTransport.SITX -> "sitx_tak_screen"
    NetworkTransport.TAK_SERVERS -> "tak_servers"
}

internal fun takServerConnectionSummary(
    config: TrackerConfig,
    states: Map<String, TakServerState>,
): String {
    val servers = config.servers
        .filter { it.enabled && it.address.isNotBlank() }
        .distinctBy { it.endpointKey }
    val connected = if (config.takServersEnabled) {
        servers.count { states[it.id]?.status == TakStatus.CONNECTED }
    } else 0
    return "$connected/${servers.size}"
}

internal fun dittoStatusResource(state: DittoState): Int = when (state) {
    DittoState.Disabled -> R.string.transport_disabled
    DittoState.NeedsIdentity -> R.string.transport_needs_identity
    DittoState.Starting -> R.string.transport_starting
    DittoState.Active -> R.string.transport_sync_active
    is DittoState.Error -> R.string.transport_error
}

internal fun TrackerConfig.sameDittoIdentity(other: TrackerConfig): Boolean =
    dittoPdlSource == other.dittoPdlSource &&
        (dittoPdlSource != DittoPdlSource.CUSTOM || dittoCustomPdl == other.dittoCustomPdl)

internal fun dittoEnableBlocker(current: TrackerConfig, requested: TrackerConfig, permissionsGranted: Boolean): Int? = when {
    current.networkPreferencesLocked -> R.string.network_settings_locked
    !current.sameDittoIdentity(requested) -> R.string.transport_identity_changed
    !permissionsGranted -> R.string.transport_nearby_required
    else -> null
}

private fun dittoEnableError(config: TrackerConfig): String? {
    val pdl = when (config.dittoPdlSource) {
        DittoPdlSource.COMMUNITY -> BuildConfig.DITTO_COMMUNITY_IDENTITY
        DittoPdlSource.CUSTOM -> config.dittoCustomPdl
    }
    return try {
        if (DittoCommunityLicenseHelper.parse(pdl).expiresAtMillis <= System.currentTimeMillis()) {
            "Ditto Edge Sync identity has expired"
        } else null
    } catch (error: IllegalArgumentException) {
        error.message ?: "Invalid Ditto Edge Sync identity"
    }
}

/** Shared by the existing plugin row and the network checklist, including delayed permission results. */
@Composable
internal fun rememberDittoToggle(
    repo: SettingsRepository,
    config: State<TrackerConfig>,
    openIdentity: () -> Unit,
): (Boolean) -> Unit {
    val context = LocalContext.current.applicationContext
    var pending by remember { mutableStateOf<TrackerConfig?>(null) }
    fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun apply(enabled: Boolean, requested: TrackerConfig) {
        var error: String? = null
        var applied = false
        saveNetworkPreference(repo, context, transform = { current ->
            val blocker = if (enabled) {
                dittoEnableBlocker(current, requested, dittoRuntimePermissions(context).isEmpty())
            } else null
            if (blocker != null) {
                error = context.getString(blocker)
                current
            } else {
                error = if (enabled) dittoEnableError(current) else null
                if (error != null) current else {
                    applied = true
                    current.copy(dittoEnabled = enabled)
                }
            }
        }) {
            error?.let(::message)
            if (applied && enabled) TrackerService.start(context)
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val requested = pending
        pending = null
        if (requested != null) {
            if (dittoRuntimePermissions(context).isEmpty()) apply(true, requested)
            else message(context.getString(R.string.transport_nearby_required))
        }
    }
    return { enabled ->
        val current = config.value
        when {
            current.networkPreferencesLocked -> message(context.getString(R.string.network_settings_locked))
            !enabled -> {
                pending = null
                apply(false, current)
            }
            else -> {
                val error = dittoEnableError(current)
                if (error != null) {
                    message(error)
                    openIdentity()
                } else {
                    val missing = dittoRuntimePermissions(context)
                    if (missing.isEmpty()) apply(true, current) else {
                        pending = current
                        permissions.launch(missing)
                    }
                }
            }
        }
    }
}
