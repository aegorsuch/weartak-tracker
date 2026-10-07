package com.tak.weartak_tracker.ui

import android.os.Build
import android.os.SystemClock
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tak.weartak_tracker.BuildConfig
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import java.time.Instant

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
    val running by TrackerState.serviceRunning.collectAsStateWithLifecycle()
    val interval by TrackerState.reportingIntervalSecs.collectAsStateWithLifecycle()
    val lastPli by TrackerState.lastPliMillis.collectAsStateWithLifecycle()
    val endpoints by TrackerState.endpoints.collectAsStateWithLifecycle()
    val access by TrackerState.locationAccess.collectAsStateWithLifecycle()
    val activity by TrackerState.activity.collectAsStateWithLifecycle()
    val servers by TrackerState.takServers.collectAsStateWithLifecycle()
    val sitx by TrackerState.sitx.collectAsStateWithLifecycle()
    val connectivity = rememberConnectivity()
    WearTAKPageWithBackArrow("Dev Debug Tools", back) {
        item { DebugStayAwakeSetting() }
        item { DebugValue("Build", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }
        item { DebugValue("Device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}") }
        item { DebugValue("UID", repo.deviceUid) }
        item { DebugValue("Network", connectivity.name) }
        item { DebugValue("Reporting", if (running) "Every ${interval}s / stale after ${CotBuilder.pliStaleSeconds(interval)}s" else "Stopped") }
        item { DebugValue("Last PLI sent", if (lastPli > 0) Instant.ofEpochMilli(lastPli).toString() else "Never") }
        item { DebugValue("Location / movement", "${access.name} / ${activity.name}") }
        item { DebugValue("Connected endpoints", endpoints.joinToString().ifEmpty { "None" }) }
        item { DebugValue("TAK servers", servers.values.joinToString { it.status.name }.ifEmpty { "None" }) }
        item { DebugValue("Sit(x)", sitx.toString()) }
    }
}

@Composable
private fun DebugValue(title: String, value: String) {
    Text("$title\n$value", modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center)
}
