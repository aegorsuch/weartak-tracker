package com.tak.weartak_tracker

import android.app.Application
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.os.PowerManager
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.diagnostics.Diagnostics
import com.tak.weartak_tracker.diagnostics.DiagnosticEvent
import com.tak.weartak_tracker.diagnostics.DiagnosticDetail
import com.tak.weartak_tracker.diagnostics.observeDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TrackerApp : Application() {
    private val resumedActivities = mutableSetOf<Activity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val uiResumed: Boolean get() = resumedActivities.isNotEmpty()

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
        val crashHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (crashHandler != null) Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Diagnostics.recordCrash()
            } finally {
                crashHandler.uncaughtException(thread, error)
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivities.add(activity)
                Diagnostics.record(DiagnosticEvent.UI, DiagnosticDetail.RESUMED)
                Diagnostics.record(DiagnosticEvent.POWER, if (getSystemService(PowerManager::class.java).isPowerSaveMode) {
                    DiagnosticDetail.POWER_SAVE
                } else DiagnosticDetail.NORMAL)
            }
            override fun onActivityPaused(activity: Activity) {
                resumedActivities.remove(activity)
                Diagnostics.record(DiagnosticEvent.UI, DiagnosticDetail.PAUSED)
            }
            override fun onActivityDestroyed(activity: Activity) { resumedActivities.remove(activity) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        settings = SettingsRepository(this)
        scope.launch {
            settings.config.map { it.diagnosticsEnabled }.distinctUntilChanged().collectLatest {
                Diagnostics.setEnabled(it)
                if (it) {
                    Diagnostics.record(DiagnosticEvent.APP, DiagnosticDetail.STARTED)
                    observeDiagnostics()
                }
            }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object { const val CHANNEL_ID = "tracker" }
}
