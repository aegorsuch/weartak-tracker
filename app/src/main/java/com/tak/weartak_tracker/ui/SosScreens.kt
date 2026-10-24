package com.tak.weartak_tracker.ui

import android.widget.Toast
import com.tak.weartak_tracker.data.manualAlertOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.data.CustomAlerts
import com.tak.weartak_tracker.data.LocationAccess
import com.tak.weartak_tracker.data.PRESET_MANUAL_ALERTS
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.service.AlertForwarder
import com.tak.weartak_tracker.service.TrackerService
import kotlinx.coroutines.delay
import java.util.UUID

internal val MANUAL_ALERT_OPTIONS = PRESET_MANUAL_ALERTS

private fun displayType(description: String) =
    description.trim().takeUnless { it.isEmpty() || it == AlertForwarder.DEFAULT_DESCRIPTION }

/**
 * Preset and saved custom alerts send on tap. When alerting, ask to cancel the last alert.
 * A result dialog follows.
 */
@Composable
fun SosScreen(repo: SettingsRepository, customAlerts: List<String>, exit: () -> Unit, addCustomAlert: () -> Unit) {
    val context = LocalContext.current
    val alerts by TrackerState.alerts.collectAsStateWithLifecycle()
    var pendingUid by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCancel by rememberSaveable { mutableStateOf(false) }
    var pendingType by rememberSaveable { mutableStateOf<String?>(null) }
    val active = alerts.filter { it.state == AlertState.ALERT }.maxByOrNull { it.timeMillis }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    fun request(cancel: Boolean, uid: String, description: String) {
        if (pendingUid != null) return
        if (!LocationAccess.current(context).granted) {
            permissions.launch(foregroundPermissions())
            return
        }
        pendingCancel = cancel
        pendingType = displayType(description)
        pendingUid = uid
        TrackerService.start(context, if (cancel) TrackerService.ACTION_CANCEL_ALERT else TrackerService.ACTION_RAISE_ALERT) {
            putExtra(TrackerService.EXTRA_UID, uid)
            putExtra(TrackerService.EXTRA_DESCRIPTION, description)
        }
    }

    val uid = pendingUid
    if (uid == null) {
        if (active == null) {
            ManualAlertListScreen(
                presets = MANUAL_ALERT_OPTIONS,
                customAlerts = customAlerts,
                onSend = { text ->
                    request(false, UUID.randomUUID().toString(), text.trim().ifEmpty { AlertForwarder.DEFAULT_DESCRIPTION })
                },
                onAdd = addCustomAlert,
                onDelete = { text -> repo.updateAsync { it.copy(customAlerts = CustomAlerts.remove(it.customAlerts, text)) } },
                onCancel = exit,
            )
        } else {
            ConfirmationAlertPage(
                text = displayType(active.description)?.let { "Cancel Alert: $it?" } ?: "Cancel Manual Alert?",
                onConfirm = { request(true, active.uid, active.description) },
                onDismiss = exit,
            )
        }
    } else {
        var timedOut by remember(uid) { mutableStateOf(false) }
        LaunchedEffect(uid) {
            // Covers the service's up-to-3 s wait for a fresh fix plus sending.
            delay(8000)
            timedOut = true
        }
        val entry = alerts.firstOrNull { it.uid == uid }
        // null = still waiting for the service to process the request.
        val result: Boolean? = if (!pendingCancel) {
            entry?.let { !it.enqueued }
        } else when {
            entry == null -> true
            entry.state == AlertState.CANCEL && entry.enqueued -> false
            else -> null
        }
        val success = result ?: if (timedOut) false else null
        if (success != null) {
            SuccessDialog(success, pendingCancel, pendingType, onDismiss = exit)
            LaunchedEffect(Unit) {
                delay(1500)
                exit()
            }
        } else {
            Column(Modifier.fillMaxSize().background(colors.background)) {}
        }
    }
}

/**
 * One full-width, single-line row per alert, then a "+" row that adds a custom alert. Saved custom
 * alerts can be deleted. The cancel button sits below the list, so it never covers a row.
 */
@Composable
fun ManualAlertListScreen(
    presets: List<String>,
    customAlerts: List<String>,
    onSend: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        Column(
            modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        text = "Manual Alert",
                        fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(manualAlertOptions(presets, customAlerts)) { option ->
                    ManualAlertRow(
                        option.label,
                        onClick = { onSend(option.label) },
                        onDelete = if (option.custom) ({ confirmDelete = option.label }) else null,
                    )
                }
                item { AddCustomAlertRow(onAdd) }
            }
            Box(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(40.dp).border(1.dp, color = Color.Black, shape = CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.Red, containerColor = Color.DarkGray),
                ) { Icon(imageVector = Icons.Filled.Close, contentDescription = "Cancel", tint = Color.Red) }
            }
        }
    }
    val pending = confirmDelete
    WearTAKAlertDialog(
        show = pending != null,
        onDismissRequest = { confirmDelete = null },
        text = { Text("Delete $pending?", textAlign = TextAlign.Center, fontSize = 14.sp) },
        confirmButton = {
            Button(
                onClick = {
                    confirmDelete = null
                    pending?.let(onDelete)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White),
            ) { Text("Delete", color = Color.White) }
        },
        dismissButton = {
            Button(
                onClick = { confirmDelete = null },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray, contentColor = Color.White),
            ) { Text("Cancel", color = Color.White) }
        },
    )
}

private val ALERT_ROW_SHAPE = RoundedCornerShape(20.dp)

/** A 911-style row: red outline and alert icon, white label on one line. */
@Composable
private fun ManualAlertRow(text: String, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(ALERT_ROW_SHAPE)
            .border(2.dp, Color.Red, ALERT_ROW_SHAPE)
            .background(Color.DarkGray)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (onDelete == null) 10.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.tak_alert_icon), contentDescription = null, tint = Color.Red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete $text", tint = Color.LightGray, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AddCustomAlertRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(ALERT_ROW_SHAPE)
            .border(2.dp, Color.Gray, ALERT_ROW_SHAPE)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Add custom alert", tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** Text entry for a new custom alert; saving adds it to the Manual Alert list without sending it. */
@Composable
fun CustomAlertEntryPage(repo: SettingsRepository, saved: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    fun save() {
        val error = CustomAlerts.validationError(saved, value.text)
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            return
        }
        repo.updateAsync { c -> CustomAlerts.add(c.customAlerts, value.text)?.let { c.copy(customAlerts = it) } ?: c }
        onBack()
    }
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            placeholder = { Text(text = CUSTOM_ALERT_HINT, color = Color.Gray, maxLines = 1) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            textStyle = TextStyle(color = Color.White),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        WearTAKConfirmCancelButtons(onClickCancel = onBack, onClickConfirm = { save() })
    }
}

const val CUSTOM_ALERT_HINT = "Type Custom Alert"
/** CIV's ConfirmationAlertPage as shown while alerting (red background). */
@Composable
fun ConfirmationAlertPage(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        containerColor = Color.Red,
        modifier = Modifier.fillMaxSize().background(Color.Red).padding(0.dp),
        onDismissRequest = onDismiss,
        icon = {
            Icon(painterResource(R.drawable.tak_alert_icon), modifier = Modifier.size(35.dp), contentDescription = null, tint = Color.White)
        },
        text = {
            Text(
                text = text,
                textAlign = TextAlign.Center,
                fontSize = 10.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(0.45f),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
            ) {
                androidx.compose.material3.Text(text = "Confirm", maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxWidth(0.45f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
            ) {
                androidx.compose.material3.Text(text = "Cancel", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}

/** CIV's SuccessDialogActivity, driven by the store-and-forward result. */
@Composable
fun SuccessDialog(success: Boolean, cancel: Boolean, type: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxSize().height(200.dp),
            colors = CardDefaults.cardColors(containerColor = colors.background),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (success) Icons.Rounded.Check else Icons.Rounded.Clear,
                    modifier = Modifier.size(25.dp),
                    contentDescription = null,
                    tint = if (success) Color.Green else Color.Red,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = if (success) "Success!" else "Error", textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = when {
                        !success && cancel -> "Cancel Alert will be sent when reconnected"
                        !success -> "Alert will be sent when reconnected"
                        cancel -> type?.let { "Alert Cancelled: $it" } ?: "Manual Alert Cancelled"
                        else -> type?.let { "Alert Sent: $it" } ?: "Manual Alert Sent"
                    },
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
