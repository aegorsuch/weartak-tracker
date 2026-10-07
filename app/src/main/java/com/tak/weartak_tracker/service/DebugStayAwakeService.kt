package com.tak.weartak_tracker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DebugStayAwakeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var screenLock: PowerManager.WakeLock? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var watching = false
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            checkWirelessDebugging()
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (checkWirelessDebugging()) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            finishSession()
            return START_NOT_STICKY
        }
        showNotification()
        if (!checkWirelessDebugging()) return START_NOT_STICKY
        if (mutableActive.value) return START_NOT_STICKY

        try {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(WIRELESS_DEBUGGING_SETTING), false, observer,
            )
            watching = true
            acquireLocks()
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot start debug stay awake", error)
            Toast.makeText(this, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
            finishSession()
            return START_NOT_STICKY
        }
        mutableActive.value = true
        handler.postDelayed(watchdog, POLL_INTERVAL_MS)
        Log.i(TAG, "Debug stay awake enabled until Wireless debugging is disabled")
        return START_NOT_STICKY
    }

    private fun checkWirelessDebugging(): Boolean {
        val enabled = try {
            Settings.Global.getInt(contentResolver, WIRELESS_DEBUGGING_SETTING, 0) == 1
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot read Wireless debugging setting; disabling stay awake", error)
            false
        }
        if (!enabled) {
            Log.i(TAG, "Wireless debugging disabled or unavailable; releasing debug locks")
            if (!mutableActive.value) {
                Toast.makeText(this, R.string.debug_stay_awake_requires_wireless, Toast.LENGTH_LONG).show()
            }
            finishSession()
        }
        return enabled
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        screenLock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "$TAG:screen").apply {
            setReferenceCounted(false)
            acquire()
        }
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.debug_stay_awake_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DebugStayAwakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.location_on)
            .setContentTitle(getString(R.string.debug_stay_awake))
            .setContentText(getString(R.string.debug_stay_awake_active))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.debug_stay_awake_warning)))
            .setContentIntent(open)
            .addAction(0, getString(R.string.debug_stay_awake_stop), stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun releaseLocks() {
        if (watching) {
            contentResolver.unregisterContentObserver(observer)
            watching = false
        }
        handler.removeCallbacks(watchdog)
        wifiLock?.let { if (it.isHeld) it.release() }
        cpuLock?.let { if (it.isHeld) it.release() }
        screenLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        cpuLock = null
        screenLock = null
        mutableActive.value = false
    }

    private fun finishSession() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        Log.i(TAG, "Debug stay awake stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WearTAK-DebugStayAwake"
        private const val WIRELESS_DEBUGGING_SETTING = "adb_wifi_enabled"
        private const val POLL_INTERVAL_MS = 2_000L
        const val ACTION_STOP = "com.tak.weartak_tracker.DEBUG_STAY_AWAKE_STOP"
        private const val CHANNEL_ID = "debug_stay_awake"
        private const val NOTIFICATION_ID = 64_001
        private val mutableActive = MutableStateFlow(false)
        val active = mutableActive.asStateFlow()
    }
}
