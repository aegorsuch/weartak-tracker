package com.tak.weartak_tracker.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.tak.weartak_tracker.data.LocationAccess
import com.tak.weartak_tracker.data.heartRatePermission
import com.tak.weartak_tracker.data.SettingsRepository
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
        setContent { MaterialTheme { WearTAKTextSelection { TrackerNav(repo) } } }
    }
}

typealias Navigate = (String) -> Unit

fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun foregroundPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun TrackerNav(repo: SettingsRepository) {
    val nav = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    val config = repo.config.collectAsStateWithLifecycle(initialValue = null).value ?: return
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        TrackerService.start(context)
    }
    LaunchedEffect(Unit) {
        val missing = buildList {
            if (!LocationAccess.current(context).granted) {
                addAll(foregroundPermissions().filterNot(context::granted))
            }
            val heartRatePermission = heartRatePermission()
            if (!context.granted(heartRatePermission)) {
                add(heartRatePermission)
            }
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        else TrackerService.start(context)
    }
    // Navigation retains its graph callbacks; they must read the latest settings.
    val currentConfig = rememberUpdatedState(config)
    val go: Navigate = { route ->
        if (currentConfig.value.networkPreferencesLocked && isNetworkSettingsRoute(route)) {
            Toast.makeText(context, "Network settings are locked", Toast.LENGTH_SHORT).show()
        } else {
            nav.navigate(route)
        }
    }
    val back: () -> Unit = { nav.popBackStack() }
    val home: () -> Unit = { nav.popBackStack("main_screen", inclusive = false) }
    SwipeDismissableNavHost(navController = nav, startDestination = "main_screen") {
        composable("main_screen") { MainScreen(currentConfig.value, go) }
        composable("sos_screen") { SosScreen(repo, home) }
        settingsGraph(repo, currentConfig, go, back)
    }
}

@Composable
fun MainScreen(config: TrackerConfig, go: Navigate) {
    val context = LocalContext.current
    val alerts by TrackerState.alerts.collectAsStateWithLifecycle()
    val isAlerting = alerts.any { it.state == AlertState.ALERT }

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
                Callsign(config.callsign) {
                    TrackerService.start(context, TrackerService.ACTION_REBROADCAST_PLI)
                    go("settings_screen")
                }
            }
        }
        StatusIcons(
            config,
            onNetworkClick = { go("network_preferences") },
            onLocationClick = { go("reporting_strategy") },
        )
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
        modifier = Modifier.fillMaxWidth(0.85f),
        leadingIcon = {
            Icon(painter = painterResource(R.drawable.settings), contentDescription = "Settings", modifier = Modifier.padding(0.dp))
        },
        label = {
            Text(
                callsign,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                overflow = TextOverflow.Ellipsis,
                fontSize = if (callsign.length <= maxChar) 15.sp else 13.sp,
            )
        },
        shape = RoundedCornerShape(40.dp),
    )
}

/** Connection/location (top) and network/battery (bottom) icons, positioned like CIV's TroubleshootingIcons. */
@Composable
private fun StatusIcons(config: TrackerConfig, onNetworkClick: () -> Unit, onLocationClick: () -> Unit) {
    val endpoints by TrackerState.endpoints.collectAsStateWithLifecycle()
    val takStates by TrackerState.takServers.collectAsStateWithLifecycle()
    val sitx by TrackerState.sitx.collectAsStateWithLifecycle()
    val running by TrackerState.serviceRunning.collectAsStateWithLifecycle()
    val access by TrackerState.locationAccess.collectAsStateWithLifecycle()

    val icon = connectionStatusIcon(config, takStates, endpoints, sitx)
    val locationOn = running

    Box(modifier = Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.TopCenter) {
        // 40 dp touch targets centred where the 25/20 dp icons used to sit.
        Box(
            modifier = Modifier.align(Alignment.CenterStart).offset(x = 32.dp, y = (-10).dp).size(40.dp)
                .clip(CircleShape).clickable(onClickLabel = "Network Preferences", onClick = onNetworkClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(painter = painterResource(icon), contentDescription = "Connection status", modifier = Modifier.size(25.dp))
        }
        val approximate = locationOn && access == LocationAccess.APPROXIMATE
        Box(
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-30).dp, y = (-10).dp).size(40.dp)
                .clip(CircleShape).clickable(onClickLabel = "Reporting Strategy", onClick = onLocationClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (locationOn) R.drawable.location_on else R.drawable.location_off),
                contentDescription = if (approximate) "Location reporting (approximate)" else "Location reporting",
                modifier = Modifier.size(20.dp),
                tint = when {
                    approximate -> Color.Yellow
                    locationOn -> Color.White
                    else -> Color.DarkGray
                },
            )
        }

        val connectivity = rememberConnectivity()
        val online = connectivity != Connectivity.NONE && connectivity != Connectivity.AIRPLANE
        Icon(
            painterResource(connectivityIcon(connectivity)),
            contentDescription = "Network: ${connectivity.name.lowercase()}",
            modifier = Modifier.align(Alignment.CenterStart).offset(x = 15.dp, y = 10.dp).size(25.dp),
            tint = if (online) Color.White else Color.Unspecified,
        )
        val battery = rememberBatteryPercent()
        Icon(
            painterResource(batteryIcon(battery)),
            contentDescription = "Battery $battery%",
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-15).dp, y = 10.dp).size(20.dp),
            tint = if (battery <= 20) Color.Red else Color.White,
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
