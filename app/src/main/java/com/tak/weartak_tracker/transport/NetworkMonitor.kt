package com.tak.weartak_tracker.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the default (internet) network for TAK Server/SITX reconnects and any Wi-Fi network
 * (even without internet) for multicast.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _defaultNetwork = MutableStateFlow<Network?>(null)
    val defaultNetwork: StateFlow<Network?> = _defaultNetwork.asStateFlow()

    private val _wifiNetwork = MutableStateFlow<Network?>(null)
    val wifiNetwork: StateFlow<Network?> = _wifiNetwork.asStateFlow()

    private val wifiNetworks = linkedSetOf<Network>()

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _defaultNetwork.value = network }
        override fun onLost(network: Network) {
            if (_defaultNetwork.value == network) _defaultNetwork.value = null
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(wifiNetworks) {
            wifiNetworks += network
            _wifiNetwork.value = network
        }

        override fun onLost(network: Network) = synchronized(wifiNetworks) {
            wifiNetworks -= network
            _wifiNetwork.value = wifiNetworks.lastOrNull()
        }
    }

    fun start() {
        cm.registerDefaultNetworkCallback(defaultCallback)
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(wifiRequest, wifiCallback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(defaultCallback) }
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
    }
}
