package com.tak.weartak_tracker.ui

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.service.DebugStayAwakeService
import com.tak.weartak_tracker.service.DebugStayAwakeSession

@Composable
internal fun DebugStayAwakeSetting() {
    val context = LocalContext.current
    val active by DebugStayAwakeService.active.collectAsStateWithLifecycle()
    var resumeAfterUpdate by remember(context) { mutableStateOf(DebugStayAwakeSession.resumeAfterUpdate(context)) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ToggleChip(
                modifier = Modifier.weight(0.55f),
                checked = active,
                onCheckedChange = { enabled ->
                    val intent = Intent(context, DebugStayAwakeService::class.java)
                    if (enabled) {
                        if (!DebugStayAwakeSession.setExplicitlyEnabled(context, true)) {
                            Toast.makeText(context, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
                            return@ToggleChip
                        }
                        intent.action = DebugStayAwakeService.ACTION_START
                        try {
                            ContextCompat.startForegroundService(context, intent)
                        } catch (error: SecurityException) {
                            Log.e("WearTAK-DebugStayAwake", "Cannot start debug service", error)
                            DebugStayAwakeSession.setExplicitlyEnabled(context, false)
                            Toast.makeText(context, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        DebugStayAwakeSession.setExplicitlyEnabled(context, false)
                        context.stopService(intent)
                    }
                },
                label = { Text(stringResource(R.string.debug_stay_awake)) },
                toggleControl = { Switch(checked = active, onCheckedChange = null) },
            )
            Row(
                modifier = Modifier.weight(0.45f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = resumeAfterUpdate,
                    onCheckedChange = { checked ->
                        if (DebugStayAwakeSession.setResumeAfterUpdate(context, checked)) {
                            resumeAfterUpdate = checked
                        } else {
                            Toast.makeText(context, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.debug_stay_awake_resume_after_update),
                    fontSize = 9.sp,
                )
            }
        }
        if (active) {
            Text(stringResource(R.string.debug_stay_awake_warning))
        }
    }
}
