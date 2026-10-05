package com.tak.weartak_tracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.tak.weartak_tracker.data.SettingsRepository

class TrackerApp : Application() {
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object { const val CHANNEL_ID = "tracker" }
}
