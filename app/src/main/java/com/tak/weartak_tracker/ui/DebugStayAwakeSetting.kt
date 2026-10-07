package com.tak.weartak_tracker.ui

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.service.DebugStayAwakeService

@Composable
internal fun DebugStayAwakeSetting() {
    val context = LocalContext.current
    val active by DebugStayAwakeService.active.collectAsStateWithLifecycle()
    WearTAKToggleChip(
        checked = active,
        onCheckedChange = { enabled ->
            val intent = Intent(context, DebugStayAwakeService::class.java)
            if (enabled) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (error: SecurityException) {
                    Log.e("WearTAK-DebugStayAwake", "Cannot start debug service", error)
                    Toast.makeText(context, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
                }
            } else {
                context.stopService(intent)
            }
        },
        title = context.getString(R.string.debug_stay_awake),
        description = context.getString(
            if (active) R.string.debug_stay_awake_active else R.string.debug_stay_awake_warning,
        ),
    )
}
