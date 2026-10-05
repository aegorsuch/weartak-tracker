package com.tak.weartak_tracker.transport

import android.net.Network
import android.util.Log
import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.TrackerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/** Send-only UDP multicast publisher bound to the Wi-Fi network (same as CIV's MulticastPublisher). */
class MulticastPublisher(private val onStarted: () -> Unit) {
    private var socket: MulticastSocket? = null
    private var group: InetAddress? = null
    private var port = 0
    private var key: Triple<Boolean, String, Int>? = null
    private var network: Network? = null

    val running: Boolean get() = socket != null

    @Synchronized
    fun configure(enabled: Boolean, address: String, port: Int, wifi: Network?) {
        val nextKey = Triple(enabled, address.trim(), port)
        if (nextKey == key && wifi == network) return
        key = nextKey
        network = wifi
        close()
        if (!enabled || wifi == null) return
        try {
            val ms = MulticastSocket(null)
            ms.reuseAddress = true
            ms.timeToLive = 1
            ms.bind(null)
            wifi.bindSocket(ms)
            group = InetAddress.getByName(address.trim())
            this.port = port
            socket = ms
            TrackerState.setEndpoint(Endpoint.MULTICAST, true)
            onStarted()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start multicast publisher", e)
            close()
        }
    }

    suspend fun send(xml: String): Int = withContext(Dispatchers.IO) {
        val (s, g, p) = synchronized(this@MulticastPublisher) { Triple(socket, group, port) }
        if (s == null || g == null) return@withContext 0
        val bytes = xml.toByteArray(Charsets.UTF_8)
        if (bytes.size > 1300) Log.w(TAG, "Multicast CoT is ${bytes.size} bytes and may be fragmented")
        try {
            s.send(DatagramPacket(bytes, bytes.size, g, p))
            1
        } catch (e: Exception) {
            Log.w(TAG, "Multicast send failed", e)
            0
        }
    }

    @Synchronized
    fun close() {
        runCatching { socket?.close() }
        socket = null
        TrackerState.setEndpoint(Endpoint.MULTICAST, false)
    }

    companion object { private const val TAG = "MulticastPublisher" }
}
