package com.tak.weartak_tracker.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.tak.weartak_tracker.R

/** Stay awake may only run while Wi-Fi is switched on and the watch is on its charger. */
internal object DebugStayAwakeConditions {
    enum class Block(val message: Int) {
        WIFI_OFF(R.string.debug_stay_awake_stopped_wifi_off),
        UNPLUGGED(R.string.debug_stay_awake_stopped_unplugged),
    }

    fun startBlock(wifiEnabled: Boolean, pluggedIn: Boolean): Block? = when {
        !wifiEnabled -> Block.WIFI_OFF
        !pluggedIn -> Block.UNPLUGGED
        else -> null
    }

    /** Maps a power/Wi-Fi broadcast to the trigger that must end the session, if any. */
    fun stopTrigger(action: String?, wifiState: Int): Block? = when (action) {
        Intent.ACTION_POWER_DISCONNECTED -> Block.UNPLUGGED
        WifiManager.WIFI_STATE_CHANGED_ACTION -> when (wifiState) {
            WifiManager.WIFI_STATE_DISABLING, WifiManager.WIFI_STATE_DISABLED -> Block.WIFI_OFF
            else -> null
        }
        else -> null
    }

    fun current(context: Context): Block? = startBlock(wifiEnabled(context), pluggedIn(context))

    private fun wifiEnabled(context: Context): Boolean =
        context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true

    private fun pluggedIn(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }
}
