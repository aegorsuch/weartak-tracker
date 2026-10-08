package com.tak.weartak_tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class DebugStayAwakeUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!DebugStayAwakeSession.isEligibleForUpdateResume(context)) return

        val serviceIntent = Intent(context, DebugStayAwakeService::class.java)
            .setAction(DebugStayAwakeService.ACTION_RESTORE_AFTER_UPDATE)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot restore debug stay awake after update", error)
        }
    }

    private companion object {
        const val TAG = "WearTAK-DebugStayAwake"
    }
}
