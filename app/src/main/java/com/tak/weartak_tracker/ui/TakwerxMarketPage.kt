package com.tak.weartak_tracker.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.TrackerApp
import com.tak.weartak_tracker.market.MarketCatalogException
import com.tak.weartak_tracker.market.MarketInstallCoordinator
import com.tak.weartak_tracker.market.MarketInstallResult
import com.tak.weartak_tracker.market.MarketInstallState
import com.tak.weartak_tracker.market.MarketInstallStatusReceiver
import com.tak.weartak_tracker.market.MarketPlugin
import com.tak.weartak_tracker.market.MarketRefreshStatus
import kotlinx.coroutines.launch

@Composable
internal fun TakwerxMarketPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val updateMonitor = remember(context.applicationContext) {
        (context.applicationContext as TrackerApp).marketUpdateMonitor
    }
    val snapshot by updateMonitor.snapshot.collectAsStateWithLifecycle()
    val hostName = stringResource(R.string.market_host_name)
    val permissionNeeded = stringResource(R.string.market_permission_needed)
    val permissionSettingsError = stringResource(R.string.market_permission_settings_error)
    val installerConfirmation = stringResource(R.string.market_installer_confirmation)
    val installError = stringResource(R.string.market_install_error)
    val installStateMessages = mapOf(
        MarketInstallState.INSTALL to stringResource(R.string.market_install),
        MarketInstallState.UPDATE to stringResource(R.string.market_update),
        MarketInstallState.UP_TO_DATE to stringResource(R.string.market_up_to_date),
        MarketInstallState.NEWER_VERSION_INSTALLED to stringResource(R.string.market_newer_installed),
        MarketInstallState.INCOMPATIBLE to stringResource(R.string.market_incompatible),
        MarketInstallState.SIGNER_MISMATCH to stringResource(R.string.market_signer_mismatch),
        MarketInstallState.PLAY_MANAGED_HOST to stringResource(R.string.market_play_managed),
        MarketInstallState.PACKAGE_QUERY_FAILED to stringResource(R.string.market_package_unavailable),
    )
    val catalogRepository = updateMonitor.catalogRepository
    val installer = remember(catalogRepository) { MarketInstallCoordinator(context, catalogRepository) }
    val scope = rememberCoroutineScope()
    val catalog = snapshot.catalog
    val loading = snapshot.status == MarketRefreshStatus.CHECKING
    var operationMessage by remember { mutableStateOf<String?>(null) }
    var operationPackage by remember { mutableStateOf<String?>(null) }
    var activePackage by remember { mutableStateOf<String?>(null) }
    var installedRevision by remember { mutableIntStateOf(0) }
    val displayPlugins = remember(catalog, installedRevision, hostName) {
        marketPluginsInDisplayOrder(catalog?.plugins.orEmpty(), hostName).map { plugin ->
            try {
                MarketPluginDisplay(
                    plugin = plugin,
                    installState = installer.installState(plugin),
                    lastResult = MarketInstallStatusReceiver.lastStatus(context, plugin.packageName),
                )
            } catch (error: MarketCatalogException) {
                Log.e(TAG, "Cannot verify installed plugin state: ${plugin.packageName}", error)
                MarketPluginDisplay(
                    plugin = plugin,
                    installState = MarketInstallState.PACKAGE_QUERY_FAILED,
                    lastResult = null,
                )
            }
        }
    }

    LaunchedEffect(snapshot.availableUpdates) {
        if (snapshot.availableUpdates.isNotEmpty()) updateMonitor.markUpdatesReviewed()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                installedRevision++
                updateMonitor.retryInterruptedRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun install(plugin: MarketPlugin) {
        scope.launch {
            operationMessage = null
            operationPackage = plugin.packageName
            MarketInstallStatusReceiver.clearStatus(context, plugin.packageName)
            installedRevision++
            if (!installer.canInstallPackages()) {
                operationMessage = permissionNeeded
                try {
                    context.startActivity(installer.installPermissionIntent())
                } catch (error: Exception) {
                    Log.e(TAG, "Cannot open unknown-source install settings", error)
                    operationMessage = permissionSettingsError
                }
                return@launch
            }

            activePackage = plugin.packageName
            try {
                when (val result = installer.install(plugin)) {
                    is MarketInstallResult.Submitted -> operationMessage = installerConfirmation
                    MarketInstallResult.PermissionRequired -> {
                        operationMessage = permissionNeeded
                        try {
                            context.startActivity(installer.installPermissionIntent())
                        } catch (error: Exception) {
                            Log.e(TAG, "Cannot open unknown-source install settings", error)
                            operationMessage = permissionSettingsError
                        }
                    }
                    is MarketInstallResult.Blocked -> {
                        operationMessage = installStateMessages.getValue(result.state)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Plugin install failed: ${plugin.packageName}", error)
                operationMessage = error.message ?: installError
            } finally {
                activePackage = null
                installedRevision++
            }
        }
    }

    WearTAKPageWithBackArrow(
        title = stringResource(R.string.takwerx_market_title),
        onBack = onBack,
    ) {
        item {
            Chip(
                onClick = updateMonitor::refresh,
                enabled = updateMonitor.isConfigured && !loading,
                label = {
                    Text(stringResource(if (loading) R.string.market_loading else R.string.market_refresh))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val statusMessage = when (snapshot.status) {
            MarketRefreshStatus.NOT_CONFIGURED -> R.string.market_not_configured
            MarketRefreshStatus.OFFLINE -> R.string.market_offline
            MarketRefreshStatus.CHECKING -> if (catalog == null) R.string.market_loading else null
            MarketRefreshStatus.INVALID_CATALOG -> R.string.market_catalog_invalid
            MarketRefreshStatus.UNSUPPORTED_TARGET -> R.string.market_unsupported_target
            MarketRefreshStatus.UNAVAILABLE -> R.string.market_catalog_unavailable
            MarketRefreshStatus.READY -> when {
                snapshot.availableUpdates.isNotEmpty() -> null
                catalog?.plugins.isNullOrEmpty() -> R.string.market_empty
                else -> R.string.market_no_updates
            }
        }
        statusMessage?.let { message ->
            item {
                Text(
                    stringResource(message),
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    color = if (snapshot.status == MarketRefreshStatus.INVALID_CATALOG ||
                        snapshot.status == MarketRefreshStatus.UNSUPPORTED_TARGET ||
                        snapshot.status == MarketRefreshStatus.UNAVAILABLE
                    ) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
                )
            }
        }
        displayPlugins.forEach { display ->
            item {
                MarketPluginEntry(
                    plugin = display.plugin,
                    installState = display.installState,
                    canStartInstall = activePackage == null,
                    installing = activePackage == display.plugin.packageName,
                    installResult = if (operationPackage == display.plugin.packageName) {
                        display.lastResult?.second ?: operationMessage
                    } else {
                        display.lastResult?.second
                    },
                    installStateMessages = installStateMessages,
                    onInstall = { install(display.plugin) },
                )
            }
        }
    }
}

internal fun marketPluginsInDisplayOrder(plugins: List<MarketPlugin>, hostName: String): List<MarketPlugin> =
    plugins.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { plugin ->
        if (plugin.hostUpdate && plugin.packageName == com.tak.weartak_tracker.BuildConfig.APPLICATION_ID) {
            hostName
        } else plugin.name
    })

private data class MarketPluginDisplay(
    val plugin: MarketPlugin,
    val installState: MarketInstallState,
    val lastResult: Pair<String, String>?,
)

@Composable
private fun MarketPluginEntry(
    plugin: MarketPlugin,
    installState: MarketInstallState,
    canStartInstall: Boolean,
    installing: Boolean,
    installResult: String?,
    installStateMessages: Map<MarketInstallState, String>,
    onInstall: () -> Unit,
) {
    val download = installState == MarketInstallState.INSTALL || installState == MarketInstallState.UPDATE
    val current = installState == MarketInstallState.UP_TO_DATE ||
        installState == MarketInstallState.NEWER_VERSION_INSTALLED
    val statusText = installStateMessages.getValue(installState)
    val detail = when {
        installing -> null
        !download && !current -> statusText
        else -> installResult ?: statusText
    }
    val progressDescription = stringResource(R.string.market_download_verifying)
    Chip(
        onClick = onInstall,
        enabled = canStartInstall && download,
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (plugin.hostUpdate && plugin.packageName == com.tak.weartak_tracker.BuildConfig.APPLICATION_ID) {
                        stringResource(R.string.market_host_name)
                    } else {
                        stringResource(R.string.market_plugin_by_publisher, plugin.name, plugin.publisher)
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).semantics {
                            contentDescription = progressDescription
                        },
                    )
                } else if (download) {
                    Text(statusText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (current) {
                    Icon(Icons.Filled.Check, contentDescription = statusText)
                }
            }
        },
        secondaryLabel = detail?.let { message -> { Text(message) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val TAG = "TakwerxMarketPage"
