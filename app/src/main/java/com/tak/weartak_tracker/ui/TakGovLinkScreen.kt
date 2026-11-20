package com.tak.weartak_tracker.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Text
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TakGovLinkStatus
import com.tak.weartak_tracker.transport.TakGovDeviceClient
import com.tak.weartak_tracker.transport.TakGovDeviceCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val DEVICE_VERIFICATION_URL = "https://tak.gov/register-device"

@Composable
internal fun TakGovLinkScreen(repo: SettingsRepository, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(repo) { TakGovDeviceClient(repo) }
    val status by repo.takGovLinkStatus.collectAsStateWithLifecycle(TakGovLinkStatus.UNLINKED)
    var code by remember { mutableStateOf<TakGovDeviceCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(client) { client.refreshAccessLevelIfDue() }

    WearTAKPageWithBackArrow("My TAK.gov Link", back) {
        item {
            Text(
                status.subtitle,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
            )
        }
        item {
            WearTAKTitleChip(
                if (status == TakGovLinkStatus.UNLINKED) "Link TAK.gov" else "Re-link TAK.gov",
                enabled = !busy,
            ) {
                busy = true
                code = null
                error = null
                scope.launch {
                    try {
                        client.link { code = it }
                        code = null
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        error = failure.message ?: "TAK.gov authorization failed"
                    } finally {
                        busy = false
                    }
                }
            }
        }
        if (busy) {
            item { androidx.wear.compose.material.CircularProgressIndicator() }
        }
        code?.let { deviceCode ->
            item {
                Text("Enter this code at TAK.gov:", textAlign = TextAlign.Center, fontSize = 12.sp)
            }
            item {
                SelectionContainer {
                    Text(
                        deviceCode.userCode,
                        textAlign = TextAlign.Center,
                        fontSize = 20.sp,
                    )
                }
            }
            item {
                WearTAKTitleChip("Open TAK.gov registration") {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEVICE_VERIFICATION_URL)))
                    }.onFailure {
                        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            item {
                WearTAKTitleChip("Copy code") {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("TAK.gov device code", deviceCode.userCode))
                }
            }
        }
        error?.let { message ->
            item { Text(message, color = Color.Red, textAlign = TextAlign.Center, fontSize = 12.sp) }
        }
        if (status != TakGovLinkStatus.UNLINKED) {
            item {
                Button(
                    onClick = { confirmRemove = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White),
                ) { Text("Remove Link") }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove TAK.gov link?") },
            text = { Text("This removes the saved link from this watch.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        scope.launch { repo.clearTakGovLink() }
                        code = null
                        error = null
                    },
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }
}
