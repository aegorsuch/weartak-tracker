package com.tak.weartak_tracker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DebugStayAwakeService : Service() {
    private var screenLock: PowerManager.WakeLock? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            finishSession()
            return START_NOT_STICKY
        }
        showNotification()
        if (mutableActive.value) return START_NOT_STICKY

        try {
            acquireLocks()
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot start debug stay awake", error)
            Toast.makeText(this, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
            finishSession()
            return START_NOT_STICKY
        }
        mutableActive.value = true
        Log.i(TAG, "Debug stay awake enabled until manually stopped or the app process ends")
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        // A window flag cannot keep the display awake after leaving the app UI.
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                getString(R.string.debug_stay_awake_active) + "\n" +
                    getString(R.string.debug_stay_awake_warning),
            ))
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
        const val ACTION_STOP = "com.tak.weartak_tracker.DEBUG_STAY_AWAKE_STOP"
        private const val CHANNEL_ID = "debug_stay_awake"
        private const val NOTIFICATION_ID = 64_001
        private val mutableActive = MutableStateFlow(false)
        val active = mutableActive.asStateFlow()
    }
}
