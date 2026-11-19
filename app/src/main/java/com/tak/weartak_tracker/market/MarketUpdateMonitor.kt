package com.tak.weartak_tracker.market

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.net.ssl.SSLException

internal enum class MarketRefreshStatus {
    NOT_CONFIGURED,
    OFFLINE,
    CHECKING,
    READY,
    INVALID_CATALOG,
    UNAVAILABLE,
}

internal data class MarketUpdateSnapshot(
    val status: MarketRefreshStatus,
    val catalog: MarketCatalog? = null,
    val availableUpdates: List<MarketPlugin> = emptyList(),
    val showUpdateWarning: Boolean = false,
)

internal class MarketUpdateReview {
    private var reviewed = false

    fun markReviewed() {
        reviewed = true
    }

    fun shouldShowWarning(updates: Collection<MarketPlugin>): Boolean =
        updates.isNotEmpty() && !reviewed
}

internal suspend fun <T> retryMarketCatalogFetch(
    waitForRetry: suspend () -> Unit,
    hasInternet: () -> Boolean,
    fetch: suspend () -> T,
): T = try {
    fetch()
} catch (error: IOException) {
    if (error is SSLException) throw error
    waitForRetry()
    if (!hasInternet()) throw error
    fetch()
}

internal class MarketUpdateMonitor(
    context: Context,
    internal val catalogRepository: MarketCatalogRepository =
        MarketCatalogRepository(MarketConfiguration.fromBuildConfig()),
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val installer = MarketInstallCoordinator(appContext, catalogRepository)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var started = false
    private var internetAvailable = false
    private var refreshJob: Job? = null
    private val updateReview = MarketUpdateReview()

    private val _snapshot = MutableStateFlow(
        MarketUpdateSnapshot(
            status = if (MarketConfiguration.fromBuildConfig().isConfigured) {
                MarketRefreshStatus.OFFLINE
            } else {
                MarketRefreshStatus.NOT_CONFIGURED
            },
        ),
    )
    val snapshot: StateFlow<MarketUpdateSnapshot> = _snapshot.asStateFlow()
    val isConfigured: Boolean = MarketConfiguration.fromBuildConfig().isConfigured

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            setInternetAvailable(
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        }

        override fun onLost(network: Network) {
            setInternetAvailable(hasValidatedInternet())
        }
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        if (!isConfigured) return

        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Cannot monitor default network for Market catalog updates", error)
        }
        setInternetAvailable(hasValidatedInternet())
    }

    fun refresh() {
        if (!isConfigured) {
            _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.NOT_CONFIGURED)
            return
        }
        synchronized(lock) {
            if (!internetAvailable) {
                _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.OFFLINE)
                return
            }
            if (refreshJob?.isActive == true) return
            _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.CHECKING)
            refreshJob = scope.launch { refreshCatalog() }
        }
    }

    fun retryInterruptedRefresh() {
        when (_snapshot.value.status) {
            MarketRefreshStatus.OFFLINE, MarketRefreshStatus.UNAVAILABLE ->
                setInternetAvailable(hasValidatedInternet(), retryIfUnchanged = true)
            else -> Unit
        }
    }

    fun markUpdatesReviewed() {
        synchronized(lock) {
            val current = _snapshot.value
            if (current.availableUpdates.isEmpty()) return
            updateReview.markReviewed()
            _snapshot.value = current.copy(showUpdateWarning = false)
        }
    }

    private suspend fun refreshCatalog() {
        try {
            val catalog = retryMarketCatalogFetch(
                waitForRetry = { delay(CATALOG_RETRY_DELAY_MS) },
                hasInternet = ::hasValidatedInternet,
            ) {
                catalogRepository.fetchCatalog(appContext.packageName)
            }
            val updates = mutableListOf<MarketPlugin>()
            var verificationFailed = false
            catalog.plugins.forEach { plugin ->
                try {
                    if (installer.installState(plugin) == MarketInstallState.UPDATE) {
                        updates += plugin
                    }
                } catch (error: MarketCatalogException) {
                    verificationFailed = true
                    Log.e(TAG, "Cannot verify installed package for Market update: ${plugin.packageName}", error)
                }
            }
            synchronized(lock) {
                _snapshot.value = MarketUpdateSnapshot(
                    status = if (verificationFailed) MarketRefreshStatus.UNAVAILABLE else MarketRefreshStatus.READY,
                    catalog = catalog,
                    availableUpdates = updates,
                    showUpdateWarning = updateReview.shouldShowWarning(updates),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MarketCatalogException) {
            Log.e(TAG, "Could not load signed Market catalog", error)
            if (error.invalidCatalog) {
                _snapshot.value = MarketUpdateSnapshot(status = MarketRefreshStatus.INVALID_CATALOG)
            } else {
                _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.UNAVAILABLE)
            }
        } catch (error: IOException) {
            Log.i(TAG, "Market catalog transport failed", error)
            val status = if (hasValidatedInternet()) MarketRefreshStatus.UNAVAILABLE else MarketRefreshStatus.OFFLINE
            _snapshot.value = _snapshot.value.copy(status = status)
        } catch (error: Exception) {
            Log.e(TAG, "Could not check signed Market catalog", error)
            _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.UNAVAILABLE)
        }
    }

    private fun setInternetAvailable(available: Boolean, retryIfUnchanged: Boolean = false) {
        val shouldRefresh = synchronized(lock) {
            val shouldRefresh = available && (!internetAvailable || retryIfUnchanged)
            internetAvailable = available
            if (!available && _snapshot.value.status != MarketRefreshStatus.NOT_CONFIGURED) {
                _snapshot.value = _snapshot.value.copy(status = MarketRefreshStatus.OFFLINE)
            }
            shouldRefresh
        }
        if (shouldRefresh) refresh()
    }

    private fun hasValidatedInternet(): Boolean {
        val activeNetwork = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun close() {
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
        }
        scope.cancel()
    }

    private companion object {
        const val CATALOG_RETRY_DELAY_MS = 2_000L
        const val TAG = "TakwerxMarket"
    }
}
