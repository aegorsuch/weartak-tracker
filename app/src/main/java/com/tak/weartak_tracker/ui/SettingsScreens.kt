package com.tak.weartak_tracker.ui

import android.Manifest
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
import com.tak.weartak_tracker.data.ROLE_CATEGORIES
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.SitxState
import com.tak.weartak_tracker.data.SitxTokens
import com.tak.weartak_tracker.data.TEAMS
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TakStatus
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.service.TrackerService
import com.tak.weartak_tracker.transport.SitxClient
import kotlinx.coroutines.launch

private const val INTERVAL_MIN = 1
private const val INTERVAL_MAX = 1_000_000

/** Config update helper used by all settings pages. */
@Composable
private fun rememberUpdater(repo: SettingsRepository): ((TrackerConfig) -> TrackerConfig) -> Unit {
    val scope = rememberCoroutineScope()
    return { transform -> scope.launch { repo.update(transform) } }
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
        val update = rememberUpdater(repo)
        val context = LocalContext.current
        val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        WearTAKPageWithBackArrow("Callsign and Device Preferences", back) {
            item { WearTAKTitleChipWithState("My Callsign", config.callsign) { go("my_callsign") } }
            item { WearTAKTitleChipWithState("My Team", config.team) { go("my_team") } }
            item { WearTAKTitleChipWithState("My Role", config.role) { go("my_role") } }
            item { HorizontalDivider(Modifier.padding(vertical = 5.dp), color = Color.DarkGray) }
            item {
                WearTAKToggleChip(
                    checked = config.reportingEnabled,
                    onCheckedChange = { on ->
                        update { it.copy(reportingEnabled = on) }
                        if (on) TrackerService.start(context) else TrackerService.stop(context)
                    },
                    title = "Location Reporting",
                    description = "Service ON/OFF",
                )
            }
            item {
                WearTAKTitleChipWithState(
                    "Reporting Strategy",
                    if (config.dynamicReporting) "Dynamic Reporting" else "Constant Reporting",
                ) { go("reporting_strategy") }
            }
            item {
                val granted = context.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                WearTAKTitleChipWithState("Background Location", if (granted) "Allowed" else "Tap to allow (needed at boot)") {
                    if (!granted) {
                        if (context.granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                            backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else {
                            Toast.makeText(context, "Allow location first", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }

    composable("my_callsign") {
        val update = rememberUpdater(repo)
        WearTAKStringEntryPage(
            item = config.callsign,
            label = "My Callsign",
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
                WearTAKTitleChipWithState(
                    category.category,
                    if (config.role in category.roles) config.role else "",
                ) { go("role_category_screen/$index") }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }

    composable(
        "role_category_screen/{index}",
        arguments = listOf(navArgument("index") { type = NavType.IntType }),
    ) { entry ->
        val update = rememberUpdater(repo)
        val category = ROLE_CATEGORIES.getOrNull(entry.arguments?.getInt("index") ?: 0) ?: ROLE_CATEGORIES.first()
        WearTAKSelectionPage(category.category, category.roles, config.role, onBack = back) { role ->
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
            if (config.dynamicReporting) {
                item { WearTAKTitleChipWithState("Stationary Reporting Interval", "${config.stationaryInterval}s") { go("interval/stationary") } }
                item { WearTAKTitleChipWithState("On Foot Reporting Interval", "${config.onFootInterval}s") { go("interval/onfoot") } }
                item { WearTAKTitleChipWithState("Vehicle Reporting Interval", "${config.vehicleInterval}s") { go("interval/vehicle") } }
                item { WearTAKTitleChipWithState("While Alerting Reporting Interval", "${config.alertingInterval}s") { go("interval/alerting") } }
            } else {
                item { WearTAKTitleChipWithState("Constant Reporting Interval", "${config.constantInterval}s") { go("interval/constant") } }
            }
            item { Spacer(Modifier.height(10.dp)) }
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

    composable("interval/{kind}") { entry ->
        val update = rememberUpdater(repo)
        val kind = entry.arguments?.getString("kind").orEmpty()
        val (label, value) = when (kind) {
            "stationary" -> "Stationary Reporting Interval" to config.stationaryInterval
            "onfoot" -> "On Foot Reporting Interval" to config.onFootInterval
            "vehicle" -> "Vehicle Reporting Interval" to config.vehicleInterval
            "alerting" -> "While Alerting Reporting Interval" to config.alertingInterval
            else -> "Constant Reporting Interval" to config.constantInterval
        }
        WearTAKIntEntryPage(value, label, INTERVAL_MIN, INTERVAL_MAX, back) { v ->
            update {
                when (kind) {
                    "stationary" -> it.copy(stationaryInterval = v)
                    "onfoot" -> it.copy(onFootInterval = v)
                    "vehicle" -> it.copy(vehicleInterval = v)
                    "alerting" -> it.copy(alertingInterval = v)
                    else -> it.copy(constantInterval = v)
                }
            }
        }
    }

    composable("network_preferences") {
        WearTAKPageWithBackArrow("Network Preferences", back) {
            item { WearTAKTitleChip("TAK Servers") { go("tak_servers") } }
            item {
                WearTAKTitleChipWithState("TAK SA Multicast", if (config.multicastEnabled) "Enabled" else "Disabled") {
                    go("tak_sa_multicast")
                }
            }
            item {
                WearTAKTitleChipWithState("Sit(x) TAK", if (config.sitxEnabled) "Enabled" else "Disabled") {
                    go("sitx_tak_screen")
                }
            }
        }
    }

    composable("tak_servers") { TakServersPage(repo, config, go, back) }

    composable("new_server_screen") {
        ServerFormPage(repo, "New Server", TakServerConfig(), isNew = true, back = back)
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

    composable("multicast_port") {
        val update = rememberUpdater(repo)
        WearTAKIntEntryPage(config.multicastPort, "Port", 1, 65535, back) { v -> update { it.copy(multicastPort = v) } }
    }

    composable("sitx_tak_screen") { SitxPage(repo, config, go, back) }

    composable("sitx_tak_url_screen") {
        val update = rememberUpdater(repo)
        WearTAKStringEntryPage(item = config.sitxUrl, label = "Address", onBack = back) { v ->
            update { it.copy(sitxUrl = v.trim(), sitxGroup = "") }
        }
    }

    composable("sitx_tak_client_id_screen") {
        val update = rememberUpdater(repo)
        WearTAKStringEntryPage(item = config.sitxClientId, label = "Client ID", onBack = back) { v ->
            update { it.copy(sitxClientId = v.trim()) }
        }
    }

    composable("sitx_tak_group_screen") { SitxGroupPage(repo, config, back) }

    composable("sitx_status_authorization_screen") { SitxStatusPage(back) }
}

private fun isMulticastAddress(s: String): Boolean {
    val parts = s.split(".")
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    return octets.all { it in 0..255 } && octets[0] in 224..239
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
                        if (on && config.reportingEnabled) TrackerService.start(context)
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
            val p = port.trim().toIntOrNull()
            if (name.isBlank() || address.isBlank() || p == null || p !in 1..65535 || username.isBlank() || password.isBlank()) {
                Toast.makeText(context, "Please enter valid values for all fields", Toast.LENGTH_LONG).show()
            } else {
                val server = initial.copy(
                    name = name.trim(), address = address.trim(), port = p,
                    username = username.trim(), password = password, enabled = true,
                )
                scope.launch {
                    repo.update { c ->
                        c.copy(
                            reportingEnabled = true,
                            servers = if (isNew) c.servers + server else c.servers.map { if (it.id == server.id) server else it },
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
private fun SitxPage(repo: SettingsRepository, config: TrackerConfig, go: Navigate, back: () -> Unit) {
    val update = rememberUpdater(repo)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sitx by TrackerState.sitx.collectAsStateWithLifecycle()
    val groups by TrackerState.sitxGroups.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    val groupName = groups.firstOrNull { it.flowTag == config.sitxGroup }?.name
        ?: config.sitxGroup.ifBlank { "No Group Selected" }
    val hasConfig = config.sitxEnabled || config.sitxUrl.isNotBlank() || config.sitxClientId.isNotBlank() ||
        config.sitxGroup.isNotBlank()

    WearTAKPageWithBackArrow("Sit(x) TAK", back) {
        item {
            WearTAKToggleChip(
                checked = config.sitxEnabled,
                onCheckedChange = { on ->
                    update { it.copy(sitxEnabled = on, reportingEnabled = it.reportingEnabled || on) }
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
        item {
            WearTAKTitleChipWithState("Client ID", config.sitxClientId.ifBlank { "Not set" }) { go("sitx_tak_client_id_screen") }
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
                enabled = hasConfig,
                contentPadding = PaddingValues(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White),
            ) { Text(text = "Remove", color = Color.White) }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }

    WearTAKAlertDialog(
        show = confirmRemove,
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove Sit(x)?", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Text(
                "Clears the address, client ID, group and authorization",
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            DialogButton("Remove", Color.Red, Color.White) {
                confirmRemove = false
                scope.launch {
                    // Disable first so the running client stops before its tokens are wiped.
                    repo.update { it.copy(sitxEnabled = false, sitxUrl = "", sitxClientId = "", sitxGroup = "") }
                    repo.setSitxTokens(SitxTokens())
                    TrackerState.sitxGroups.value = emptyList()
                    Toast.makeText(context, "Sit(x) configuration removed", Toast.LENGTH_SHORT).show()
                }
            }
        },
        dismissButton = { DialogButton("Cancel", Color.Gray, Color.White) { confirmRemove = false } },
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

