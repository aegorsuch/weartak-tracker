package com.tak.weartak_tracker.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.tak.weartak_tracker.diagnostics.AppLog as Log
import java.io.Closeable

internal class MulticastWifiRequest(
    private val acquireLock: () -> Unit,
    private val releaseLock: () -> Unit,
    private val requestNetwork: () -> Closeable,
    private val onFailure: (Exception) -> Unit,
) : Closeable {
    private var request: Closeable? = null

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            close()
            return
        }
        if (request != null) return
        try {
            acquireLock()
            request = requestNetwork()
        } catch (e: Exception) {
            onFailure(e)
        } finally {
            if (request == null) release()
        }
    }

    override fun close() {
        val registered = request ?: return
        request = null
        try {
            registered.close()
        } catch (e: Exception) {
            onFailure(e)
        } finally {
            release()
        }
    }

    private fun release() {
        try {
            releaseLock()
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    companion object {
        private const val TAG = "MulticastWifiRequest"

        fun create(context: Context): MulticastWifiRequest {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            var lock: WifiManager.WifiLock? = null
            val handler = Handler(Looper.getMainLooper())
            return MulticastWifiRequest(
                acquireLock = {
                    // Android 14+ maps this to low latency, effective only screen-on/in foreground.
                    @Suppress("DEPRECATION")
                    val activeLock = lock ?: wifi.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi"
                    ).apply { setReferenceCounted(false) }.also { lock = it }
                    activeLock.acquire()
                },
                releaseLock = { lock?.let { if (it.isHeld) it.release() } },
                requestNetwork = {
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        private val capabilities = mutableMapOf<Network, Pair<Boolean, Boolean>>()
                        private val interfaces = mutableMapOf<Network, String?>()

                        override fun onAvailable(network: Network) {
                            Log.i(TAG, "Multicast Wi-Fi available: $network")
                        }

                        override fun onLost(network: Network) {
                            capabilities.remove(network)
                            interfaces.remove(network)
                            // Keep demand and the power lock while Wear OS finds Wi-Fi again.
                            Log.i(TAG, "Multicast Wi-Fi lost: $network; waiting for Wi-Fi")
                        }

                        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                            val state = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) to
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                            if (capabilities.put(network, state) == state) return
                            Log.i(TAG, "Multicast Wi-Fi capabilities: $network " +
                                "validated=${state.first} notSuspended=${state.second}")
                        }

                        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                            val name = properties.interfaceName
                            if (interfaces.containsKey(network) && interfaces[network] == name) return
                            interfaces[network] = name
                            Log.i(TAG, "Multicast Wi-Fi link: $network interface=$name")
                        }
                    }
                    val networkRequest = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.requestNetwork(networkRequest, callback, handler)
                    Log.i(TAG, "Requested local Wi-Fi and power lock for multicast (internet not required)")
                    Closeable {
                        try {
                            cm.unregisterNetworkCallback(callback)
                        } finally {
                            Log.i(TAG, "Released multicast Wi-Fi request")
                        }
                    }
                },
                // Exception messages may include network details; log only the failure type.
                onFailure = { Log.w(TAG, "Multicast Wi-Fi retention failed: ${it.javaClass.simpleName}") },
            )
        }
    }
}
