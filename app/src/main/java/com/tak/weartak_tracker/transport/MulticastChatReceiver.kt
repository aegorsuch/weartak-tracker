package com.tak.weartak_tracker.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import com.tak.weartak_tracker.cot.TakDatagramDecoder
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Listens for mesh (multicast) CoT so direct chat commands work without a TAK Server, like WearOS
 * WearTAK. Only runs while chat commands and multicast are both on and Wi-Fi is up; every datagram is
 * handed to [onEvent] as XML with a `multicast:<group>:<port>` source id.
 */
class MulticastChatReceiver(
    context: Context,
    private val onEvent: (sourceId: String, xml: String) -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    private var key: Pair<Set<Pair<String, Int>>, Network?>? = null
    private val sockets = mutableListOf<MulticastSocket>()

    @Synchronized
    fun configure(enabled: Boolean, groups: Set<Pair<String, Int>>, wifi: Network?) {
        val nextKey = (if (enabled && wifi != null) groups else emptySet()) to wifi
        if (nextKey == key) return
        key = nextKey
        stopLocked()
        if (!enabled || wifi == null) return
        val iface = runCatching { cm?.getLinkProperties(wifi)?.interfaceName?.let(NetworkInterface::getByName) }.getOrNull()
        for ((address, port) in groups) {
            try {
                val group = InetAddress.getByName(address)
                val socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1000
                    bind(InetSocketAddress(port))
                }
                wifi.bindSocket(socket)
                if (iface != null) {
                    socket.networkInterface = iface
                    socket.joinGroup(InetSocketAddress(group, port), iface)
                } else {
                    @Suppress("DEPRECATION")
                    socket.joinGroup(group)
                }
                sockets += socket
                Thread({ receive(socket, "multicast:$address:$port") }, "mcast-chat-$port").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                Log.w(TAG, "Unable to listen on multicast $address:$port", e)
            }
        }
        if (sockets.isNotEmpty()) {
            lock = wifiManager?.createMulticastLock(TAG)?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun receive(socket: MulticastSocket, sourceId: String) {
        val buffer = ByteArray(MAX_DATAGRAM)
        while (!socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                TakDatagramDecoder.decode(packet.data.copyOf(packet.length))?.let { onEvent(sourceId, it) }
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (!socket.isClosed) Log.w(TAG, "Multicast receive failed on $sourceId", e)
                return
            }
        }
    }

    @Synchronized
    fun close() {
        key = null
        stopLocked()
    }

    private fun stopLocked() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }

    companion object {
        private const val TAG = "MulticastChatReceiver"
        private const val MAX_DATAGRAM = 64 * 1024

        /** ATAK's default mesh chat group; direct chat to this tracker arrives here. */
        const val CHAT_ADDRESS = "224.10.10.1"
        const val CHAT_PORT = 17012
    }
}
