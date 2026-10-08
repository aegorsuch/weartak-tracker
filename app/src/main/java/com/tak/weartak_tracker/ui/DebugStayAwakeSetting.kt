package com.tak.weartak_tracker.ui

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.service.DebugStayAwakeService

@Composable
internal fun DebugStayAwakeSetting() {
    val context = LocalContext.current
    val active by DebugStayAwakeService.active.collectAsStateWithLifecycle()
    Column {
        ToggleChip(
            modifier = Modifier.fillMaxWidth(),
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
            label = { Text(stringResource(R.string.debug_stay_awake)) },
            toggleControl = { Switch(checked = active, onCheckedChange = null) },
        )
        if (active) {
            Text(stringResource(R.string.debug_stay_awake_warning))
        }
    }
}
