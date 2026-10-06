package com.tak.weartak_tracker.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.tak.weartak_tracker.R

/** Watch network as shown by CIV's bottom-left icon. */
enum class Connectivity { WIFI, CELL, BLUETOOTH, AIRPLANE, NONE }

/** Battery percentage, updated from the sticky ACTION_BATTERY_CHANGED broadcast. */
@Composable
fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(100) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) percent = level * 100 / scale
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { receiver.onReceive(context, it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent
}

/** Transport of the default network; Wear routes through the phone over Bluetooth when it can. */
@Composable
fun rememberConnectivity(): Connectivity {
    val context = LocalContext.current
    var state by remember { mutableStateOf(Connectivity.NONE) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun update(caps: NetworkCapabilities?) {
            state = when {
                caps == null -> if (airplaneMode(context)) Connectivity.AIRPLANE else Connectivity.NONE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Connectivity.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Connectivity.CELL
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> Connectivity.BLUETOOTH
                else -> Connectivity.WIFI
            }
        }
        update(cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) })
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(caps)
            override fun onLost(network: Network) = update(null)
        }
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess && cm != null
        // Airplane mode changes without a capability change when there was no network to begin with.
        val airplane = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) =
                update(cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) })
        }
        ContextCompat.registerReceiver(
            context, airplane, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
            context.unregisterReceiver(airplane)
        }
    }
    return state
}

private fun airplaneMode(context: Context) =
    Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

fun connectivityIcon(c: Connectivity) = when (c) {
    Connectivity.WIFI -> R.drawable.wifi
    Connectivity.CELL -> R.drawable.cell
    Connectivity.BLUETOOTH -> R.drawable.bluetooth
    Connectivity.AIRPLANE -> R.drawable.airplane_mode
    Connectivity.NONE -> R.drawable.no_cell_wifi
}

/** CIV's battery icon thresholds. */
fun batteryIcon(percent: Int) = when {
    percent <= 20 -> R.drawable.battery_1_bar
    percent <= 40 -> R.drawable.battery_3
    percent <= 60 -> R.drawable.battery_4_bar
    percent <= 80 -> R.drawable.battery_5
    else -> R.drawable.battery_full
}
