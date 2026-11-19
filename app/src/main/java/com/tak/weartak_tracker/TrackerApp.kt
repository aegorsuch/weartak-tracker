package com.tak.weartak_tracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.market.MarketUpdateMonitor

class TrackerApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    internal val marketUpdateMonitor by lazy { MarketUpdateMonitor(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        marketUpdateMonitor.start()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object { const val CHANNEL_ID = "tracker" }
}
