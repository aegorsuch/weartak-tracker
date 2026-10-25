package com.tak.weartak_tracker.ui

import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import com.tak.weartak_tracker.diagnostics.AppLog as Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tak.weartak_tracker.BuildConfig
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.data.sortedByDisplayedLabel
import com.tak.weartak_tracker.diagnostics.Diagnostics
import com.tak.weartak_tracker.diagnostics.DiagnosticsStatus
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import java.time.Instant
import java.util.Locale

internal class DeveloperModeTaps {
    private var count = 0
    private var lastTapMillis = 0L

    /** Returns taps remaining, or zero when the toggle gesture completes. */
    fun tap(nowMillis: Long): Int {
        if (nowMillis - lastTapMillis > 1500L) count = 0
        lastTapMillis = nowMillis
        count++
        if (count == 8) {
            count = 0
            return 0
        }
        return 8 - count
    }
}

internal enum class DeveloperEntry(val labelRes: Int, val primaryToggle: Boolean = false) {
    BUILD(R.string.developer_build),
    DEVICE(R.string.developer_device),
    DIAGNOSTICS(R.string.diagnostics, true),
    ENDPOINTS(R.string.developer_endpoints),
    LAST_PLI(R.string.developer_last_pli),
    LOCATION(R.string.developer_location),
    NETWORK(R.string.developer_network),
    REPORTING(R.string.developer_reporting),
    RESUME_AFTER_UPDATE(R.string.debug_stay_awake_resume_after_update),
    SITX(R.string.developer_sitx),
    STAY_AWAKE(R.string.debug_stay_awake, true),
    TAK(R.string.developer_tak),
    UID(R.string.developer_uid),
}

internal fun developerEntries(labels: Map<DeveloperEntry, String>, locale: Locale): List<DeveloperEntry> =
    listOf(true, false).flatMap { primary ->
        sortedByDisplayedLabel(DeveloperEntry.entries.filter { it.primaryToggle == primary }, locale) { labels.getValue(it) }
    }

internal fun isNetworkSettingsRoute(route: String): Boolean =
    route in setOf(
        "network_preferences", "tak_servers", "tak_channels", "new_server_screen",
        "tak_sa_multicast", "multicast_address", "multicast_protocol", "multicast_port",
        "sitx_tak_screen", "sitx_tak_url_screen", "sitx_tak_group_screen", "sitx_status_authorization_screen",
    ) || route.startsWith("edit_server_screen/") || route.startsWith("tak_channels/")

@Composable
internal fun DeveloperVersion(repo: SettingsRepository, config: TrackerConfig) {
    val context = LocalContext.current
    val taps = remember { DeveloperModeTaps() }
    var toast by remember { mutableStateOf<Toast?>(null) }
    DisposableEffect(Unit) { onDispose { toast?.cancel() } }
    Column(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            fontSize = 11.sp,
            color = if (config.developerMode) Color.Red else colors.primary,
            modifier = Modifier.clickable {
                val remaining = taps.tap(SystemClock.elapsedRealtime())
                val message = when {
                    remaining == 0 -> {
                        repo.updateAsync { it.copy(developerMode = !it.developerMode) }
                        if (config.developerMode) "Debug tools disabled" else "Debug tools enabled"
                    }
                    remaining <= 3 -> "$remaining more ${if (remaining == 1) "tap" else "taps"} to toggle debug"
                    else -> null
                }
                if (message != null) {
                    toast?.cancel()
                    toast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
                }
            },
        )
        if (config.developerMode) Text("Code ${BuildConfig.VERSION_CODE}", fontSize = 9.sp, color = Color.Red)
    }
}

@Composable
internal fun DeveloperDebugPage(repo: SettingsRepository, back: () -> Unit) {
    val config by repo.config.collectAsStateWithLifecycle(TrackerConfig())
    val diagnosticStatus by Diagnostics.status.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val running by TrackerState.serviceRunning.collectAsStateWithLifecycle()
    val interval by TrackerState.reportingIntervalSecs.collectAsStateWithLifecycle()
    val lastPli by TrackerState.lastPliMillis.collectAsStateWithLifecycle()
    val endpoints by TrackerState.endpoints.collectAsStateWithLifecycle()
    val access by TrackerState.locationAccess.collectAsStateWithLifecycle()
    val activity by TrackerState.activity.collectAsStateWithLifecycle()
    val servers by TrackerState.takServers.collectAsStateWithLifecycle()
    val sitx by TrackerState.sitx.collectAsStateWithLifecycle()
    val connectivity = rememberConnectivity()
    val labels = DeveloperEntry.entries.associateWith { stringResource(it.labelRes) }
    val entries = developerEntries(labels, LocalConfiguration.current.locales[0])
    WearTAKPageWithBackArrow("Developer Preferences", back) {
        entries.forEach { entry ->
            item(key = entry.name) {
                val title = labels.getValue(entry)
                when (entry) {
                    DeveloperEntry.STAY_AWAKE -> DebugStayAwakeSetting()
                    DeveloperEntry.RESUME_AFTER_UPDATE -> DebugStayAwakeResumeSetting()
                    DeveloperEntry.DIAGNOSTICS -> Column {
                        WearTAKToggleChip(
                            checked = config.diagnosticsEnabled,
                            onCheckedChange = { enabled ->
                                repo.updateAsync(onUpdated = {}, onError = { error ->
                                    Log.e("DeveloperSettings", "Unable to save Logging preference", error)
                                    Handler(Looper.getMainLooper()).post {
                                        Toast.makeText(context, R.string.developer_save_failed, Toast.LENGTH_LONG).show()
                                    }
                                }) { it.copy(diagnosticsEnabled = enabled) }
                            },
                            title = title,
                        )
                        if (diagnosticStatus == DiagnosticsStatus.UNAVAILABLE) {
                            Text(stringResource(R.string.diagnostics_unavailable))
                        }
                    }
                    DeveloperEntry.BUILD -> DebugValue(title, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    DeveloperEntry.DEVICE -> DebugValue(title, "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
                    DeveloperEntry.UID -> DebugValue(title, repo.deviceUid)
                    DeveloperEntry.NETWORK -> DebugValue(title, connectivity.name)
                    DeveloperEntry.REPORTING -> DebugValue(title, if (running) "Every ${interval}s / stale after ${CotBuilder.pliStaleSeconds(interval)}s" else "Stopped")
                    DeveloperEntry.LAST_PLI -> DebugValue(title, if (lastPli > 0) Instant.ofEpochMilli(lastPli).toString() else "Never")
                    DeveloperEntry.LOCATION -> DebugValue(title, "${access.name} / ${activity.name}")
                    DeveloperEntry.ENDPOINTS -> DebugValue(title, endpoints.joinToString().ifEmpty { "None" })
                    DeveloperEntry.TAK -> DebugValue(title, servers.values.joinToString { it.status.name }.ifEmpty { "None" })
                    DeveloperEntry.SITX -> DebugValue(title, sitx.toString())
                }
            }
        }
    }
}

@Composable
private fun DebugValue(title: String, value: String) {
    Text("$title\n$value", modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center)
}
