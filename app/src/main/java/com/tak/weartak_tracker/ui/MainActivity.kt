package com.tak.weartak_tracker.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.AnchorType
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.foundation.curvedRow
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.TrackerApp
import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.SitxState
import com.tak.weartak_tracker.data.TakStatus
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.service.TrackerService
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as TrackerApp).settings
        setContent { MaterialTheme { TrackerNav(repo) } }
    }
}

typealias Navigate = (String) -> Unit

fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun foregroundPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun TrackerNav(repo: SettingsRepository) {
    val nav = rememberSwipeDismissableNavController()
    val config = repo.config.collectAsStateWithLifecycle(initialValue = null).value ?: return
    val go: Navigate = { nav.navigate(it) }
    val back: () -> Unit = { nav.popBackStack() }
    val home: () -> Unit = { nav.popBackStack("main_screen", inclusive = false) }
    SwipeDismissableNavHost(navController = nav, startDestination = "main_screen") {
        composable("main_screen") { MainScreen(config, go) }
        composable("sos_screen") { SosScreen(repo, home) }
        settingsGraph(repo, config, go, back)
    }
}

@Composable
fun MainScreen(config: TrackerConfig, go: Navigate) {
    val context = LocalContext.current
    val alerts by TrackerState.alerts.collectAsStateWithLifecycle()
    val isAlerting = alerts.any { it.state == AlertState.ALERT }

    // Reporting starts only when the user opens the app; nothing is started at boot.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        TrackerService.start(context)
    }
    LaunchedEffect(Unit) {
        if (!context.granted(Manifest.permission.ACCESS_FINE_LOCATION)) permissions.launch(foregroundPermissions())
        else TrackerService.start(context)
    }

    Box(modifier = Modifier.background(colors.background).fillMaxSize().padding(5.dp)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 45.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SOSButton(isAlerting) { go("sos_screen") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Callsign(config.callsign) { go("settings_screen") }
            }
        }
        StatusIcons(config)
        TimeWithSecondsAware()
    }
}

@Composable
fun SOSButton(isAlerting: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .border(width = 4.dp, color = Color.Red, shape = RoundedCornerShape(40.dp))
            .size(110.dp, 60.dp),
        shape = RoundedCornerShape(40.dp),
        contentPadding = PaddingValues.Absolute(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isAlerting) Color.Red else Color.Transparent,
            contentColor = if (isAlerting) Color.White else Color.Red,
        ),
    ) {
        Icon(painterResource(R.drawable.tak_alert_icon), modifier = Modifier.size(35.dp), contentDescription = "Alert", tint = Color.White)
    }
}

@Composable
fun Callsign(callsign: String, onClick: () -> Unit) {
    val maxChar = 10
    AssistChip(
        onClick = onClick,
        leadingIcon = {
            Icon(painter = painterResource(R.drawable.settings), contentDescription = "Settings", modifier = Modifier.padding(0.dp))
        },
        label = {
            Text(
                callsign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = if (callsign.length <= maxChar) 15.sp else 13.sp,
            )
        },
        shape = RoundedCornerShape(40.dp),
    )
}

/** Connection (top-left) and location (top-right) icons, positioned like CIV's TroubleshootingIcons. */
@Composable
private fun StatusIcons(config: TrackerConfig) {
    val endpoints by TrackerState.endpoints.collectAsStateWithLifecycle()
    val takStates by TrackerState.takServers.collectAsStateWithLifecycle()
    val sitx by TrackerState.sitx.collectAsStateWithLifecycle()
    val running by TrackerState.serviceRunning.collectAsStateWithLifecycle()

    val enabledServers = takStates.values.filter { it.status != TakStatus.DISABLED && it.status != TakStatus.DUPLICATE }
    val anyTakConnected = Endpoint.TAK_SERVER in endpoints
    val anyTakDisconnected = enabledServers.any { it.status != TakStatus.CONNECTED }
    val connecting = enabledServers.any { it.status == TakStatus.CONNECTING || it.status == TakStatus.ENROLLING } ||
        sitx is SitxState.AwaitingUser || sitx is SitxState.Connecting || sitx is SitxState.Authorized
    val icon = when {
        anyTakConnected && enabledServers.size > 1 && anyTakDisconnected -> R.drawable.tak_server_some_connected
        (anyTakConnected && !anyTakDisconnected) || Endpoint.SITX in endpoints -> R.drawable.tak_server_connected
        connecting -> R.drawable.tak_server_connecting
        Endpoint.MULTICAST in endpoints -> R.drawable.multicast_connected
        else -> R.drawable.tak_server_disconnected
    }
    val locationOn = running

    Box(modifier = Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.TopCenter) {
        Image(
            painter = painterResource(icon),
            contentDescription = "Connection status",
            modifier = Modifier.align(Alignment.CenterStart).offset(x = 40.dp, y = (-10).dp).size(25.dp),
        )
        Icon(
            painterResource(if (locationOn) R.drawable.location_on else R.drawable.location_off),
            contentDescription = "Location reporting",
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-40).dp, y = (-10).dp).size(20.dp),
            tint = if (locationOn) Color.White else Color.DarkGray,
        )
    }
}

@Composable
fun TimeWithSecondsAware() {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isActive by remember { mutableStateOf(true) }
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> isActive = event == Lifecycle.Event.ON_RESUME }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isActive) {
        while (isActive) {
            currentTime = LocalTime.now()
            delay(1000L)
        }
    }
    CurvedLayout(anchor = 90f, anchorType = AnchorType.Center, angularDirection = CurvedDirection.Angular.Reversed) {
        curvedRow {
            curvedText(currentTime.format(formatter), style = CurvedTextStyle())
        }
    }
}
