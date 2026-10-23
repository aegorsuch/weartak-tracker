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
    @Volatile private var lastDecodeLog = 0L

    /** True while the requested listeners are all up (or none were requested). */
    @Volatile var healthy = true
        private set

    /**
     * Applies the wanted state. Unchanged state is a no-op unless a previous attempt failed, so callers can
     * call this periodically to retry sockets that could not be bound or joined.
     */
    @Synchronized
    fun configure(enabled: Boolean, groups: Set<Pair<String, Int>>, wifi: Network?) {
        val nextKey = (if (enabled && wifi != null) groups else emptySet()) to wifi
        if (nextKey == key && healthy) return
        key = nextKey
        healthy = true
        stopLocked()
        if (!enabled || wifi == null) {
            Log.i(TAG, "Mesh chat listener off (enabled=$enabled, wifi=${wifi != null})")
            return
        }
        val iface = runCatching { cm?.getLinkProperties(wifi)?.interfaceName?.let(NetworkInterface::getByName) }.getOrNull()
        if (iface == null) {
            // Link properties can lag onAvailable; listen anyway but try again shortly.
            Log.i(TAG, "Wi-Fi interface not known yet; joining on the default interface and retrying")
            healthy = false
        }
        for ((address, port) in groups) {
            var socket: MulticastSocket? = null
            try {
                val group = InetAddress.getByName(address)
                val s = MulticastSocket(null)
                socket = s
                s.reuseAddress = true
                s.soTimeout = 1000
                s.bind(InetSocketAddress(port))
                wifi.bindSocket(s)
                if (iface != null) {
                    s.networkInterface = iface
                    s.joinGroup(InetSocketAddress(group, port), iface)
                } else {
                    @Suppress("DEPRECATION")
                    s.joinGroup(group)
                }
                sockets += s
                val source = "multicast:$address:$port"
                Thread({ receive(s, source) }, "mcast-chat-$port").apply { isDaemon = true }.start()
                Log.i(TAG, "Listening for mesh chat on $address:$port (${iface?.name ?: "default interface"})")
            } catch (e: Exception) {
                runCatching { socket?.close() }
                healthy = false
                Log.w(TAG, "Unable to listen on multicast $address:$port; will retry", e)
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
                val bytes = packet.data.copyOf(packet.length)
                val xml = TakDatagramDecoder.decode(bytes)
                if (xml == null) {
                    val now = System.currentTimeMillis()
                    if (now - lastDecodeLog > 60_000) {
                        lastDecodeLog = now
                        Log.i(TAG, "Undecodable ${bytes.size}-byte datagram on $sourceId from ${packet.address?.hostAddress}")
                    }
                    continue
                }
                if (xml.contains("<__chat")) {
                    Log.i(TAG, "Chat datagram on $sourceId from ${packet.address?.hostAddress} (${bytes.size} bytes)")
                }
                onEvent(sourceId, xml)
            } catch (_: SocketTimeoutException) {
            } catch (e: Exception) {
                if (!socket.isClosed) {
                    healthy = false
                    Log.w(TAG, "Multicast receive failed on $sourceId; will rebuild", e)
                }
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
