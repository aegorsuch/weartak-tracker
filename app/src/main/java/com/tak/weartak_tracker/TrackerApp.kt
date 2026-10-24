package com.tak.weartak_tracker

import android.app.Application
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import com.tak.weartak_tracker.data.SettingsRepository

class TrackerApp : Application() {
    private val resumedActivities = mutableSetOf<Activity>()
    val uiResumed: Boolean get() = resumedActivities.isNotEmpty()

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivities.add(activity) }
            override fun onActivityPaused(activity: Activity) { resumedActivities.remove(activity) }
            override fun onActivityDestroyed(activity: Activity) { resumedActivities.remove(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        settings = SettingsRepository(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object { const val CHANNEL_ID = "tracker" }
}
