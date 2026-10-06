package com.tak.weartak_tracker.ui

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.RevealValue
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.rememberRevealState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.SwipeToRevealChip
import androidx.wear.compose.material.SwipeToRevealDefaults
import androidx.wear.compose.material.SwipeToRevealPrimaryAction
import androidx.wear.compose.material.SwipeToRevealSecondaryAction
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.composable
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.cot.calculateExertion
import com.tak.weartak_tracker.data.MULTICAST_PROTOCOLS
import com.tak.weartak_tracker.data.DEFAULT_SITX_CLIENT_ID
import com.tak.weartak_tracker.data.ROLE_CATEGORIES
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.SitxState
import com.tak.weartak_tracker.data.SitxTokens
import com.tak.weartak_tracker.data.TEAMS
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TakChannelStatus
import com.tak.weartak_tracker.data.TakServerChannels
import com.tak.weartak_tracker.data.TakServerState
import com.tak.weartak_tracker.data.TakStatus
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.service.TrackerService
import com.tak.weartak_tracker.transport.SitxClient
import com.tak.weartak_tracker.transport.TakCertificates
import com.tak.weartak_tracker.data.takServerFormError
import kotlinx.coroutines.launch

private const val INTERVAL_MIN = 1
private const val INTERVAL_MAX = 1_000_000

/** Config update helper used by all settings pages. */
@Composable
private fun rememberUpdater(repo: SettingsRepository): ((TrackerConfig) -> TrackerConfig) -> Unit =
    remember(repo) { { transform -> repo.updateAsync(transform) } }

/** Reporting interval entries with CIV's titles and routes. */
private enum class IntervalSetting(
    val route: String,
    val title: String,
    val get: (TrackerConfig) -> Int,
    val set: (TrackerConfig, Int) -> TrackerConfig,
) {
    CONSTANT("constant_reporting_interval_screen", "Constant Reporting Interval", { it.constantInterval }, { c, v -> c.copy(constantInterval = v) }),
    STATIONARY("stationary_reporting_interval_screen", "Stationary Reporting Interval", { it.stationaryInterval }, { c, v -> c.copy(stationaryInterval = v) }),
    ON_FOOT("on_foot_reporting_interval_screen", "On Foot Reporting Interval", { it.onFootInterval }, { c, v -> c.copy(onFootInterval = v) }),
    VEHICLE("vehicle_reporting_interval_screen", "Vehicle Reporting Interval", { it.vehicleInterval }, { c, v -> c.copy(vehicleInterval = v) }),
    WHILE_ALERTING("while_alerting_reporting_interval_screen", "While Alerting Reporting Interval", { it.alertingInterval }, { c, v -> c.copy(alertingInterval = v) }),
}

/** Settings routes mirroring WearTAK-CIV's WearTAKSettingsActivity, reduced to tracker settings. */
fun NavGraphBuilder.settingsGraph(repo: SettingsRepository, config: TrackerConfig, go: Navigate, back: () -> Unit) {
    composable("settings_screen") {
        WearTAKPageWithBackArrow("WearTAK Preferences", back) {
            item { WearTAKTitleChip("Callsign and Device Preferences") { go("callsign_and_device_preferences") } }
            item { WearTAKTitleChip("Network Preferences") { go("network_preferences") } }
        }
    }

    composable("callsign_and_device_preferences") {
        WearTAKPageWithBackArrow("Callsign and Device Preferences", back) {
            item { WearTAKTitleChipWithState("My Callsign", config.callsign) { go("my_callsign") } }
            item { WearTAKTitleChipWithState("My Team", config.team) { go("my_team") } }
            item { WearTAKTitleChipWithState("My Role", config.role) { go("my_role") } }
            item { WearTAKTitleChip("My User Metrics") { go("my_user_metrics") } }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    thickness = 1.dp,
                    color = Color.White,
                )
            }
            item { WearTAKTitleChip("Reporting Strategy") { go("reporting_strategy") } }
            item { PhysioToggle(repo, config) }
            item {
                val update = rememberUpdater(repo)
                WearTAKToggleChip(
                    checked = config.batdokEnabled,
                    onCheckedChange = { on -> update { it.copy(batdokEnabled = on) } },
                    title = "BATDOK",
                    description = if (config.batdokEnabled) "On" else "Off",
                )
            }
        }
    }

    medicalProfileGraph(repo, config, go, back)

    composable("my_callsign") {
        val update = rememberUpdater(repo)
        WearTAKStringEntryPage(
            item = config.callsign,
            label = "Callsign",
            onBack = back,
            validate = { if (it.isBlank()) "Please enter a value" else null },
        ) { v -> update { it.copy(callsign = v.trim()) } }
    }

    composable("my_team") {
        val update = rememberUpdater(repo)
        WearTAKSelectionPage("My Team", TEAMS, config.team, onBack = back) { team ->
            update { it.copy(team = team) }
            back()
        }
    }

    composable("my_role") {
        WearTAKPageWithBackArrow("My Role", back) {
            itemsIndexed(ROLE_CATEGORIES) { index, category ->
                WearTAKTitleChip(category.category) { go("role_category_screen/$index") }
            }
        }
    }

    composable(
        "role_category_screen/{index}",
        arguments = listOf(navArgument("index") { type = NavType.IntType }),
    ) { entry ->
        val update = rememberUpdater(repo)
        val category = ROLE_CATEGORIES.getOrNull(entry.arguments?.getInt("index") ?: 0) ?: ROLE_CATEGORIES.first()
        WearTAKSelectionPage("Role-" + category.category, category.roles, config.role, onBack = back) { role ->
            update { it.copy(role = role) }
            back()
        }
    }

    composable("reporting_strategy") {
        WearTAKPageWithBackArrow("Reporting Strategy", back) {
            item {
                WearTAKTitleChipWithState(
                    "Reporting Strategy",
                    if (config.dynamicReporting) "Dynamic Reporting" else "Constant Reporting",
                ) { go("reporting_strategy_select") }
            }
            item { Spacer(Modifier.height(5.dp)) }
            if (config.dynamicReporting) {
                listOf(IntervalSetting.STATIONARY, IntervalSetting.ON_FOOT, IntervalSetting.VEHICLE, IntervalSetting.WHILE_ALERTING)
                    .forEach { setting ->
                        item { WearTAKTitleChipWithState(setting.title, setting.get(config).toString()) { go(setting.route) } }
                    }
            } else {
                val setting = IntervalSetting.CONSTANT
                item { WearTAKTitleChipWithState(setting.title, setting.get(config).toString()) { go(setting.route) } }
            }
        }
    }

    composable("reporting_strategy_select") {
        val update = rememberUpdater(repo)
        val options = listOf("Dynamic Reporting", "Constant Reporting")
        WearTAKSelectionPage(
            "Reporting Strategy",
            options,
            if (config.dynamicReporting) options[0] else options[1],
            onBack = back,
        ) { choice ->
            update { it.copy(dynamicReporting = choice == options[0]) }
            back()
        }
    }

    IntervalSetting.entries.forEach { setting ->
        composable(setting.route) {
            val update = rememberUpdater(repo)
            WearTAKIntEntryPage(setting.get(config), setting.title, INTERVAL_MIN, INTERVAL_MAX, back) { v ->
                update { setting.set(it, v) }
            }
        }
    }

    composable("network_preferences") {
        val currentConfig by repo.config.collectAsStateWithLifecycle(initialValue = config)
        WearTAKPageWithBackArrow("Network Preferences", back) {
            item { WearTAKTitleChip("TAK Servers") { go("tak_servers") } }
            item { WearTAKTitleChip("Channels") { go("tak_channels") } }
            item {
                WearTAKTitleChipWithState("TAK SA Multicast", if (config.multicastEnabled) "Enabled" else "Disabled") {
                    go("tak_sa_multicast")
                }
            }
            item {
                WearTAKTitleChipWithState("Sit(x) TAK", sitxConfigurationLabel(currentConfig)) {
                    go("sitx_tak_screen")
                }
            }
        }
    }

    composable("tak_servers") { TakServersPage(repo, config, go, back) }

    composable("tak_channels") { ChannelServersPage(config, go, back) }

    composable(
        "tak_channels/{id}",
        arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) { entry ->
        val server = config.servers.firstOrNull { it.id == entry.arguments?.getString("id") }
        if (server == null) {
            LaunchedEffect(Unit) { back() }
        } else {
            ChannelsPage(server, back)
        }
    }

    composable("new_server_screen") {
        val newId = rememberSaveable { java.util.UUID.randomUUID().toString() }
        ServerFormPage(repo, "New Server", TakServerConfig(id = newId), isNew = true, back = back)
    }

    composable(
        "edit_server_screen/{id}",
        arguments = listOf(navArgument("id") { type = NavType.StringType }),
    ) { entry ->
        val server = config.servers.firstOrNull { it.id == entry.arguments?.getString("id") }
        if (server == null) {
            LaunchedEffect(Unit) { back() }
        } else {
            ServerFormPage(repo, "Edit Server", server, isNew = false, back = back)
        }
    }

    composable("tak_sa_multicast") {
        val update = rememberUpdater(repo)
        WearTAKPageWithBackArrow("TAK SA Multicast", back) {
            item {
                WearTAKToggleChip(
                    checked = config.multicastEnabled,
                    onCheckedChange = { on -> update { it.copy(multicastEnabled = on) } },
                    title = "TAK SA Multicast",
                    description = "Service ON/OFF",
                )
            }
            item { WearTAKTitleChipWithState("Address", config.multicastAddress) { go("multicast_address") } }
            item { WearTAKTitleChipWithState("Output Protocol", config.multicastProtocol) { go("multicast_protocol") } }
            item { WearTAKTitleChipWithState("Port", config.multicastPort.toString()) { go("multicast_port") } }
        }
    }

    composable("multicast_address") {
        val update = rememberUpdater(repo)
        WearTAKStringEntryPage(
            item = config.multicastAddress,
            label = "Address",
            onBack = back,
            validate = { if (isMulticastAddress(it.trim())) null else "Please enter a valid multicast address" },
        ) { v -> update { it.copy(multicastAddress = v.trim()) } }
    }

    composable("multicast_protocol") {
        val update = rememberUpdater(repo)
        WearTAKSelectionPage("Output Protocol", MULTICAST_PROTOCOLS, config.multicastProtocol, onBack = back) { protocol ->
            update { it.copy(multicastProtocol = protocol) }
            back()
        }
    }

    composable("multicast_port") {
        val update = rememberUpdater(repo)
        WearTAKIntEntryPage(config.multicastPort, "Port", 1, 65535, back) { v -> update { it.copy(multicastPort = v) } }
    }

    composable("sitx_tak_screen") { SitxPage(repo, config, go, back) }

    composable("sitx_tak_url_screen") {
        val update = rememberUpdater(repo)
        val currentConfig by repo.config.collectAsStateWithLifecycle(initialValue = config)
        WearTAKStringEntryPage(item = currentConfig.sitxUrl, label = "Address", onBack = back) { v ->
            update { it.copy(sitxUrl = v.trim(), sitxGroup = "") }
        }
    }

    composable("sitx_tak_group_screen") {
        val currentConfig by repo.config.collectAsStateWithLifecycle(initialValue = config)
        SitxGroupPage(repo, currentConfig, back)
    }

    composable("sitx_status_authorization_screen") { SitxStatusPage(back) }
}

/** CIV's "Physiological Monitoring" switch; turning it on asks for BODY_SENSORS first. */
@Composable
private fun PhysioToggle(repo: SettingsRepository, config: TrackerConfig) {
    val context = LocalContext.current
    val update = rememberUpdater(repo)
    val bpm by TrackerState.heartRate.collectAsStateWithLifecycle()
    val skinF by TrackerState.skinTempF.collectAsStateWithLifecycle()
    val enable = {
        update { it.copy(physioMonitoring = true) }
        TrackerService.start(context)
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enable()
        else Toast.makeText(context, "Body sensor permission is required for physio monitoring", Toast.LENGTH_LONG).show()
    }
    val hasSensor = remember {
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
    }
    WearTAKToggleChip(
        checked = config.physioMonitoring,
        onCheckedChange = { on ->
            when {
                !on -> update { it.copy(physioMonitoring = false) }
                !hasSensor -> Toast.makeText(context, "This watch has no heart rate sensor", Toast.LENGTH_LONG).show()
                context.granted(Manifest.permission.BODY_SENSORS) -> enable()
                else -> request.launch(Manifest.permission.BODY_SENSORS)
            }
        },
        title = "Physiological Monitoring",
        description = if (!config.physioMonitoring) "Off" else {
            val hr = if (bpm > 0) "$bpm" else "N/A"
            val skin = skinF?.let { String.format(java.util.Locale.US, "%.1f°F", it) } ?: "N/A"
            val exertion = calculateExertion(bpm, config.medicalProfile.ageYears(), config.medicalProfile.restingHeartRateBpm)
                ?.let { String.format(java.util.Locale.US, "%.0f%%", it * 100) } ?: "N/A"
            "On · HR $hr · Skin $skin · Exert $exertion"
        },
    )
}

private fun isMulticastAddress(s: String): Boolean {
    val parts = s.split(".")
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    return octets.all { it in 0..255 } && octets[0] in 224..239
}

private fun channelSummary(server: TakServerConfig, state: TakServerState?, channels: TakServerChannels?): String = when {
    !server.enabled -> "Disabled"
    state?.status != TakStatus.CONNECTED -> "Not connected"
    channels == null -> "Not loaded"
    else -> when (channels.status) {
        TakChannelStatus.LOADING -> "Loading..."
        TakChannelStatus.EMPTY -> "No channels assigned"
        TakChannelStatus.UNSUPPORTED -> "Channels unsupported"
        TakChannelStatus.ERROR -> channels.errorMessage ?: "Channel request failed"
        TakChannelStatus.READY -> "${channels.channels.count { it.active }} of ${channels.channels.size} active"
    }
}

/** Network Preferences > Channels: one entry per TAK Server, like WearTAK-CIV's channel layers menu. */
@Composable
private fun ChannelServersPage(config: TrackerConfig, go: Navigate, back: () -> Unit) {
    val states by TrackerState.takServers.collectAsStateWithLifecycle()
    val channels by TrackerState.takChannels.collectAsStateWithLifecycle()
    WearTAKPageWithBackArrow("Channels", back) {
        if (config.servers.isEmpty()) {
            item { Text("Add a TAK Server first", textAlign = TextAlign.Center, color = Color.LightGray) }
        }
        itemsIndexed(config.servers, key = { _, s -> s.id }) { _, server ->
            WearTAKTitleChipWithState(server.name, channelSummary(server, states[server.id], channels[server.id])) {
                go("tak_channels/${server.id}")
            }
        }
    }
}

@Composable
private fun ChannelsPage(server: TakServerConfig, back: () -> Unit) {
    val context = LocalContext.current
    val states by TrackerState.takServers.collectAsStateWithLifecycle()
    val all by TrackerState.takChannels.collectAsStateWithLifecycle()
    val connected = server.enabled && states[server.id]?.status == TakStatus.CONNECTED
    val channels = all[server.id]
    val refresh = {
        TrackerService.start(context, TrackerService.ACTION_TAK_CHANNELS_REFRESH) {
            putExtra(TrackerService.EXTRA_SERVER_ID, server.id)
        }
    }
    // Like CIV, opening a server's channel list fetches the current state from the server.
    LaunchedEffect(server.id, connected) { if (connected) refresh() }
    WearTAKPageWithBackArrow(server.name, back) {
        val status = when {
            !connected -> channelSummary(server, states[server.id], channels)
            channels == null || channels.status == TakChannelStatus.READY -> null
            else -> channelSummary(server, states[server.id], channels)
        }
        if (status != null) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (channels?.status == TakChannelStatus.LOADING) CircularProgressIndicator()
                    Text(
                        status,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        color = if (channels?.status == TakChannelStatus.ERROR) Color.Red else Color.LightGray,
                    )
                }
            }
        }
        if (connected) {
            itemsIndexed(channels?.channels.orEmpty(), key = { _, c -> c.bitPosition }) { _, channel ->
                WearTAKToggleChip(
                    checked = channel.active,
                    onCheckedChange = { on ->
                        if (!channel.updating && channels?.status != TakChannelStatus.LOADING) {
                            TrackerService.start(context, TrackerService.ACTION_TAK_CHANNEL_TOGGLE) {
                                putExtra(TrackerService.EXTRA_SERVER_ID, server.id)
                                putExtra(TrackerService.EXTRA_BIT_POSITION, channel.bitPosition)
                                putExtra(TrackerService.EXTRA_ACTIVE, on)
                            }
                        }
                    },
                    title = channel.name,
                    description = if (channel.updating) "Updating..." else if (channel.active) "Active" else "Inactive",
                )
            }
            item {
                Spacer(Modifier.height(6.dp))
                WearTAKTitleChip("Refresh") { if (channels?.status != TakChannelStatus.LOADING) refresh() }
            }
        }
    }
}

@OptIn(ExperimentalWearMaterialApi::class, ExperimentalWearFoundationApi::class)
@Composable
private fun TakServersPage(repo: SettingsRepository, config: TrackerConfig, go: Navigate, back: () -> Unit) {
    val update = rememberUpdater(repo)
    val context = LocalContext.current
    val states by TrackerState.takServers.collectAsStateWithLifecycle()
    WearTAKPageWithBackArrow("TAK Server Connections", back) {
        itemsIndexed(config.servers, key = { _, s -> s.id }) { _, server ->
            val revealState = rememberRevealState()
            val scope = rememberCoroutineScope()
            val remove = { update { c -> c.copy(servers = c.servers.filterNot { it.id == server.id }) } }
            val status = states[server.id]?.status
            SwipeToRevealChip(
                revealState = revealState,
                modifier = Modifier.fillMaxWidth(),
                primaryAction = {
                    SwipeToRevealPrimaryAction(
                        revealState = revealState,
                        icon = { Icon(SwipeToRevealDefaults.Delete, "Delete") },
                        label = { Text("Delete") },
                        onClick = { remove() },
                    )
                },
                secondaryAction = {
                    SwipeToRevealSecondaryAction(
                        revealState = revealState,
                        onClick = { go("edit_server_screen/${server.id}") },
                    ) { Icon(SwipeToRevealDefaults.MoreOptions, "More Options") }
                },
                onFullSwipe = { remove() },
            ) {
                WearTAKSplitToggleChip(
                    appIcon = {
                        Image(
                            painter = painterResource(
                                when (status) {
                                    TakStatus.CONNECTED -> R.drawable.tak_server_connected
                                    TakStatus.CONNECTING, TakStatus.ENROLLING -> R.drawable.tak_server_connecting
                                    else -> R.drawable.tak_server_disconnected
                                },
                            ),
                            contentDescription = "Status",
                            modifier = Modifier.size(22.dp).padding(end = 2.dp),
                        )
                    },
                    title = server.name,
                    label = server.address,
                    checked = server.enabled,
                    onCheckedChange = { on ->
                        update { c -> c.copy(servers = c.servers.map { if (it.id == server.id) it.copy(enabled = on) else it }) }
                        if (on) TrackerService.start(context)
                    },
                    onClick = { scope.launch { revealState.animateTo(RevealValue.Revealing) } },
                )
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            WearTAKTitleChip("Add Network", icon = { Icon(Icons.Filled.Add, contentDescription = "Add") }) {
                go("new_server_screen")
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

/** New/Edit server form plus CIV's registration dialogs driven by the connection state. */
@Composable
private fun ServerFormPage(repo: SettingsRepository, title: String, initial: TakServerConfig, isNew: Boolean, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var address by rememberSaveable { mutableStateOf(initial.address) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var password by rememberSaveable { mutableStateOf(initial.password) }
    var savedId by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val states by TrackerState.takServers.collectAsStateWithLifecycle()

    WearTAKPageWithConfirmCancel(
        title = title,
        onClickCancel = back,
        onClickConfirm = {
            val error = takServerFormError(
                name, address, port, username, password,
                hasSideloadedCert = TakCertificates.hasSideloaded(context, address),
            )
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            } else {
                val p = port.trim().toInt()
                val moved = initial.endpointKey != TakServerConfig(address = address.trim(), port = p).endpointKey
                val server = initial.copy(
                    name = name.trim(), address = address.trim(), port = p,
                    username = username.trim(), password = password, enabled = true,
                ).let { if (moved) it.copy(tlsName = "", apiTlsName = "") else it }
                scope.launch {
                    repo.update { c ->
                        // Upsert: the form stays open while registering, so Save can be pressed again for a new server.
                        val exists = c.servers.any { it.id == server.id }
                        c.copy(
                            servers = if (exists) c.servers.map { if (it.id == server.id) server else it } else c.servers + server,
                        )
                    }
                    TrackerService.start(context)
                    dismissed = false
                    savedId = server.id
                }
            }
        },
        overlay = {
            val id = savedId
            if (id != null && !dismissed) {
                val state = states[id]
                val status = state?.status
                WearTAKLoadingDialog(
                    show = status == null || status == TakStatus.ENROLLING || status == TakStatus.CONNECTING ||
                        status == TakStatus.DISCONNECTED,
                    secondaryText = "Registering device with TAK Server",
                )
                WearTAKAlertDialog(
                    show = status == TakStatus.CONNECTED || status == TakStatus.FAILED || status == TakStatus.DUPLICATE ||
                        status == TakStatus.DISABLED,
                    onDismissRequest = { dismissed = true },
                    title = {
                        Text(
                            when (status) {
                                TakStatus.CONNECTED -> "Registration Complete"
                                TakStatus.DUPLICATE -> "Duplicate TAK Server"
                                else -> "Registration Failed"
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    text = {
                        Text(
                            when (status) {
                                TakStatus.CONNECTED -> "TAK Server registration succeeded"
                                TakStatus.DUPLICATE -> "This TAK Server already exists"
                                else -> state?.message ?: "Invalid Credentials, try again"
                            },
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        if (status == TakStatus.CONNECTED) {
                            DialogButton("Okay", colors.primary, Color.Black) { dismissed = true; back() }
                        } else {
                            DialogButton("Retry", colors.primary, Color.Black) { dismissed = true }
                        }
                    },
                    dismissButton = {
                        if (status != TakStatus.CONNECTED) DialogButton("Cancel", Color.Gray, Color.White) { dismissed = true; back() }
                    },
                )
            }
        },
    ) {
        item { WearTAKOutlinedTextField(name, { name = it }, "Connection Name", KeyboardType.Text) }
        item { WearTAKOutlinedTextField(address, { address = it }, "Address/URL", KeyboardType.Uri) }
        item { WearTAKOutlinedTextField(port, { port = it }, "Port", KeyboardType.Number) }
        item { WearTAKOutlinedTextField(username, { username = it }, "Username", KeyboardType.Text) }
        item { WearTAKOutlinedTextField(password, { password = it }, "Password", KeyboardType.Password, password = true) }
        val names = listOfNotNull(
            initial.tlsName.takeIf { it.isNotBlank() }?.let { "TLS name: $it" },
            initial.apiTlsName.takeIf { it.isNotBlank() && it != initial.tlsName }?.let { "API TLS name: $it" },
        )
        if (names.isNotEmpty()) {
            item {
                Text(
                    names.joinToString("\n") + "\nDiscovered from the server certificate; cleared if address or port change.",
                    fontSize = 10.sp,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun DialogButton(text: String, container: Color, content: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) { Text(text, color = content) }
}

private fun sitxStatusText(state: SitxState): String = when (state) {
    SitxState.Disabled -> "Service not enabled"
    SitxState.Idle -> "Starting"
    is SitxState.AwaitingUser -> "Authorize: ${state.userCode}"
    SitxState.Authorized -> "Authorized"
    SitxState.NoGroups -> "No groups available"
    SitxState.NeedsGroup -> "Select a group"
    SitxState.Connecting -> "Connecting"
    is SitxState.Connected -> "Connected: ${state.group}"
    is SitxState.Error -> state.message
}

@Composable
private fun SitxPage(repo: SettingsRepository, initialConfig: TrackerConfig, go: Navigate, back: () -> Unit) {
    val config by repo.config.collectAsStateWithLifecycle(initialValue = initialConfig)
    val update = rememberUpdater(repo)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sitx by TrackerState.sitx.collectAsStateWithLifecycle()
    val groups by TrackerState.sitxGroups.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    val groupName = groups.firstOrNull { it.flowTag == config.sitxGroup }?.name
        ?: config.sitxGroup.ifBlank { "No Group Selected" }

    WearTAKPageWithBackArrow("Sit(x) TAK", back) {
        item {
            WearTAKToggleChip(
                checked = config.sitxEnabled,
                onCheckedChange = { on ->
                    update { it.copy(sitxEnabled = on) }
                    if (on) TrackerService.start(context)
                },
                title = "Sit(x) TAK",
                description = "Service ON/OFF",
            )
        }
        item {
            WearTAKTitleChipWithState("Address", SitxClient.baseUrl(config.sitxUrl).removePrefix("https://").ifBlank { "Not set" }) {
                go("sitx_tak_url_screen")
            }
        }
        item { WearTAKTitleChipWithState("Group", groupName) { go("sitx_tak_group_screen") } }
        item { WearTAKTitleChipWithState("Sit(x) State", sitxStatusText(sitx)) { go("sitx_status_authorization_screen") } }
        item {
            Button(
                onClick = { TrackerService.start(context, TrackerService.ACTION_SITX_REAUTHORIZE) },
                enabled = config.sitxEnabled,
                contentPadding = PaddingValues(10.dp),
                colors = ButtonDefaults.buttonColors(colors.primary),
            ) { Text(text = "Re-Auth", color = Color.Black) }
        }
        item {
            Button(
                onClick = { confirmRemove = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White),
            ) { Text(text = "Remove", color = Color.White) }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }

    WearTAKAlertDialog(
        show = confirmRemove,
        onDismissRequest = { confirmRemove = false },
        icon = {
            Icon(Icons.Default.Warning, modifier = Modifier.size(25.dp), contentDescription = null, tint = Color.LightGray)
        },
        text = {
            Text(
                "Remove the saved Sit(x) configuration and turn the service off? You can re-enable and configure it again later.",
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            DialogButton("Remove", Color.Red, Color.White) {
                confirmRemove = false
                scope.launch {
                    // Disable first so the running client stops before its tokens are wiped.
                    repo.update { it.copy(sitxEnabled = false, sitxUrl = "", sitxClientId = DEFAULT_SITX_CLIENT_ID, sitxGroup = "") }
                    repo.setSitxTokens(SitxTokens())
                    TrackerState.sitxGroups.value = emptyList()
                }
            }
        },
        dismissButton = { DialogButton("Dismiss", Color.Gray, Color.White) { confirmRemove = false } },
    )
}

@Composable
private fun SitxGroupPage(repo: SettingsRepository, config: TrackerConfig, back: () -> Unit) {
    val update = rememberUpdater(repo)
    val context = LocalContext.current
    val groups by TrackerState.sitxGroups.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (config.sitxEnabled) TrackerService.start(context, TrackerService.ACTION_SITX_REFRESH_GROUPS)
    }
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (config.sitxEnabled) CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
                Text(
                    if (config.sitxEnabled) "Loading Sit(x) Groups" else "Enable Sit(x) TAK first",
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                WearTAKBackButton(back)
            }
        }
    } else {
        WearTAKSelectionPage(
            "Sit(x) Group",
            groups,
            groups.firstOrNull { it.flowTag == config.sitxGroup },
            label = { it.name },
            onBack = back,
        ) { group ->
            update { it.copy(sitxGroup = group.flowTag) }
            back()
        }
    }
}

@Composable
private fun SitxStatusPage(back: () -> Unit) {
    val context = LocalContext.current
    val sitx by TrackerState.sitx.collectAsStateWithLifecycle()
    val (title, code, secondary) = when (val s = sitx) {
        is SitxState.AwaitingUser -> Triple("Authorize Device", s.userCode, s.verificationUri)
        else -> Triple("Sit(x) State", "", sitxStatusText(s))
    }
    Box(Modifier.fillMaxSize().background(colors.background).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(title, textAlign = TextAlign.Center, fontSize = 15.sp)
            if (code.isNotEmpty()) Text(code, fontSize = 25.sp, color = colors.primary, textAlign = TextAlign.Center)
            Text(secondary, fontSize = 11.sp, textAlign = TextAlign.Center, color = Color.LightGray)
            Spacer(Modifier.height(10.dp))
            Row {
                DialogButton("Dismiss", Color.Gray, Color.White, back)
                Spacer(Modifier.width(8.dp))
                DialogButton(if (sitx is SitxState.AwaitingUser) "Authorize" else "Re-Auth", colors.primary, Color.Black) {
                    TrackerService.start(context, TrackerService.ACTION_SITX_REAUTHORIZE)
                }
            }
        }
    }
}
