package com.tak.weartak_tracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.data.LocationAccess
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.ui.MainActivity

internal fun requestTrackingStart(
    locationGranted: Boolean,
    serviceRunning: Boolean,
    uiResumed: Boolean,
    start: () -> Unit,
    deferred: () -> Unit,
    failed: (RuntimeException) -> Unit,
) {
    if (!locationGranted || (!serviceRunning && !uiResumed)) {
        deferred()
        return
    }
    try {
        start()
    } catch (error: IllegalStateException) {
        // Includes Android 12+ ForegroundServiceStartNotAllowedException.
        failed(error)
    } catch (error: SecurityException) {
        failed(error)
    }
}

internal object TrackingResume {
    private const val CHANNEL_ID = "tracking_resume"
    private const val NOTIFICATION_ID = 2

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.tracking_resume_channel), NotificationManager.IMPORTANCE_DEFAULT,
        ))
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID).importance == NotificationManager.IMPORTANCE_NONE
        ) {
            Log.w("TrackerService", "Tracking is stopped; resume notification is blocked by notification settings")
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.location_on)
            .setContentTitle(context.getString(R.string.tracking_resume_title))
            .setContentText(context.getString(R.string.tracking_resume_text))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.tracking_resume_text)))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build())
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class TrackingResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!isTrackingResumeBroadcast(intent?.action)) return
        if (LocationAccess.current(context).granted && !TrackerState.serviceRunning.value) {
            TrackingResume.show(context)
        }
    }
}

internal fun isTrackingResumeBroadcast(action: String?): Boolean =
    action == "android.intent.action.MY_PACKAGE_REPLACED" || action == "android.intent.action.BOOT_COMPLETED"
