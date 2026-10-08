package com.tak.weartak_tracker.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.data.LocationAccess
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TrackerState
import com.tak.weartak_tracker.service.AlertForwarder
import com.tak.weartak_tracker.service.TrackerService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

internal val MANUAL_ALERT_OPTIONS = listOf(
    "911 Alert",
    "Gate Runner",
    "Geofence Breached",
    "Gunshot",
    "Gunshot Injury",
    "In Contact",
    "Injury",
    "Ring The Bell",
    "UAS",
    "Vehicle",
).sorted()

private fun displayType(description: String) =
    description.trim().takeUnless { it.isEmpty() || it == AlertForwarder.DEFAULT_DESCRIPTION }

/**
 * Preset selections send immediately; custom text sends on confirm. When alerting,
 * ask to cancel the last alert. A result dialog follows.
 */
@Composable
fun SosScreen(repo: SettingsRepository, exit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
            TextSelectionScreen(
                titleText = "Manual Alert",
                buttonOptions = MANUAL_ALERT_OPTIONS,
                onConfirm = { text ->
                    request(false, UUID.randomUUID().toString(), text.trim().ifEmpty { AlertForwarder.DEFAULT_DESCRIPTION })
                },
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
            delay(5000)
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

/** Custom text with backspace and X / check buttons, plus a 2-column tap-to-send preset grid. */
@Composable
fun TextSelectionScreen(titleText: String, buttonOptions: List<String>, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    WearTAKPageWithConfirmCancel(
        title = titleText,
        onClickConfirm = { onConfirm(value.text) },
        onClickCancel = onCancel,
    ) {
        item {
            Row(modifier = Modifier.padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text("") },
                    modifier = if (value.text.isEmpty()) {
                        Modifier.fillMaxWidth().weight(.8F).height(30.dp)
                    } else {
                        Modifier.fillMaxWidth().weight(.8F).defaultMinSize(minHeight = 30.dp).wrapContentHeight()
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.DarkGray,
                        unfocusedContainerColor = Color.DarkGray,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        unfocusedTextColor = Color.White,
                        focusedTextColor = Color.White,
                    ),
                    shape = RoundedCornerShape(50),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(modifier = Modifier.width(5.dp))
                IconButton(
                    onClick = {
                        if (value.text.isNotEmpty()) {
                            val newText = value.text.dropLast(1)
                            value = value.copy(text = newText, selection = TextRange(newText.length))
                        }
                    },
                    modifier = Modifier.size(35.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Color.Black, containerColor = Color.Unspecified),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.backspace),
                        contentDescription = "Backspace",
                        tint = colors.primary,
                        modifier = Modifier.size(25.dp),
                    )
                }
            }
        }
        buttonOptions.chunked(2).forEach { row ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    row.forEach { option ->
                        Button(
                            onClick = { onConfirm(option) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
                            shape = RoundedCornerShape(size = 10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = option,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

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
