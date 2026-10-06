package com.tak.weartak_tracker.transport

import android.content.Context
import android.util.Log
import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TakServerState
import com.tak.weartak_tracker.data.TakStatus
import com.tak.weartak_tracker.data.TrackerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Maintains one TLS streaming connection per enabled TAK Server (send-only; inbound data is discarded). */
class TakServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deviceUid: String,
    private val onConnected: () -> Unit,
    /** A TLS name was discovered for [TakServerConfig.tlsName] (`api == false`) or [TakServerConfig.apiTlsName]. */
    private val onTlsNameDiscovered: (server: TakServerConfig, api: Boolean, name: String) -> Unit = { _, _, _ -> },
    private val onServerConnected: (serverId: String) -> Unit = {},
    /** The server announced a group/channel membership change (`t-x-g-c`). */
    private val onChannelsChanged: (serverId: String) -> Unit = {},
) {
    private class Connection(@Volatile var server: TakServerConfig) {
        var job: Job? = null
        /** Plain socket while connecting, then the TLS socket layered on it; closing either aborts I/O. */
        @Volatile var socket: Socket? = null
        @Volatile var output: OutputStream? = null
        @Volatile var credentials: TakCertificates.Credentials? = null
        val lock = Any()
    }

    private val connections = ConcurrentHashMap<String, Connection>()
    private var servers: List<TakServerConfig> = emptyList()

    val anyConnected: Boolean get() = connections.values.any { it.output != null }

    fun update(newServers: List<TakServerConfig>) {
        if (newServers == servers) return
        servers = newServers
        val wanted = mutableMapOf<String, TakServerConfig>()
        val states = mutableMapOf<String, TakServerState>()
        for (s in newServers) {
            when {
                !s.enabled || s.address.isBlank() -> states[s.id] = TakServerState(TakStatus.DISABLED)
                wanted.values.any { it.endpointKey == s.endpointKey } ->
                    states[s.id] = TakServerState(TakStatus.DUPLICATE, "Same address:port as another server")
                else -> wanted[s.id] = s
            }
        }
        connections.keys.filter { id -> wanted[id]?.connectionKey != connections[id]?.server?.connectionKey }.forEach { id ->
            connections.remove(id)?.let(::close)
        }
        // Newly discovered TLS names don't require reconnecting; just use them from the next attempt on.
        connections.forEach { (id, c) -> wanted[id]?.let { c.server = it } }
        val previous = TrackerState.takServers.value
        connections.keys.forEach { id -> previous[id]?.let { states[id] = it } }
        TrackerState.takServers.value = states
        wanted.values.filter { !connections.containsKey(it.id) }.forEach(::start)
        publishEndpoint()
    }

    /** Called on network changes: drop sockets and reconnect immediately, resetting backoff. */
    fun reconnectAll() {
        val current = connections.values.map { it.server }
        connections.values.forEach(::close)
        connections.clear()
        current.forEach(::start)
        publishEndpoint()
    }

    fun stop() {
        connections.values.forEach(::close)
        connections.clear()
        TrackerState.takServers.value = emptyMap()
        publishEndpoint()
    }

    /** Writes [xml] to every connected server; returns how many accepted it. */
    suspend fun send(xml: String): Int = withContext(Dispatchers.IO) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        var sent = 0
        for (c in connections.values) {
            val out = c.output ?: continue
            try {
                synchronized(c.lock) {
                    out.write(bytes)
                    out.flush()
                }
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "Write to ${c.server.endpointKey} failed", e)
                runCatching { c.socket?.close() }
            }
        }
        sent
    }

    /** Channel API client for a connected server, or null when it isn't connected. */
    internal fun channelApi(serverId: String): TakChannelApi? {
        val c = connections[serverId]?.takeIf { it.output != null } ?: return null
        val creds = c.credentials ?: return null
        val server = c.server
        val host = server.address.trim()
        val discover = if (server.apiTlsName.isBlank()) { name: String ->
            Log.i(TAG, "Discovered API TLS name $name for $host")
            onTlsNameDiscovered(server, true, name)
        } else null
        return TakChannelApi(host, TakCertificates.socketFactory(creds, host, server.apiTlsName, discover))
    }

    private fun start(server: TakServerConfig) {
        val c = Connection(server)
        connections[server.id] = c
        c.job = scope.launch(Dispatchers.IO) { run(c) }
    }

    private fun close(c: Connection) {
        c.job?.cancel()
        c.output = null
        c.credentials = null
        runCatching { c.socket?.close() }
        c.socket = null
    }

    private suspend fun run(c: Connection) {
        val id = c.server.id
        var attempt = 0
        while (currentCoroutineContext().isActive && connections[id] === c) {
            val server = c.server
            var sslFailure = false
            try {
                setState(server, TakStatus.CONNECTING)
                val creds = TakCertificates.obtain(context, server, deviceUid) {
                    setState(server, TakStatus.ENROLLING)
                }
                setState(server, TakStatus.CONNECTING)
                val host = server.address.trim()
                val plain = Socket()
                c.socket = plain
                plain.keepAlive = true
                plain.tcpNoDelay = true
                plain.connect(InetSocketAddress(host, server.port), 15_000)
                // SNI uses the discovered TLS name, else the configured host (not sent for IP literals). The
                // factory's trust manager rejects certificates not issued for host/tlsName during the handshake.
                val sniHost = server.tlsName.ifBlank { host }
                val discover = if (server.tlsName.isBlank()) { name: String ->
                    Log.i(TAG, "Discovered TLS name $name for ${server.endpointKey}")
                    c.server = c.server.copy(tlsName = name)
                    onTlsNameDiscovered(server, false, name)
                } else null
                val factory = TakCertificates.socketFactory(creds, host, server.tlsName, discover)
                val socket = factory.createSocket(plain, sniHost, server.port, true) as SSLSocket
                c.socket = socket
                socket.startHandshake()
                c.credentials = creds
                c.output = socket.outputStream
                attempt = 0
                setState(server, TakStatus.CONNECTED, "Certificate: ${creds.source}")
                publishEndpoint()
                onConnected()
                onServerConnected(id)
                val input = socket.inputStream
                val buf = ByteArray(8192)
                var tail = ""
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // Inbound CoT is otherwise unused; only watch for the server's channel-change notice.
                    val text = tail + String(buf, 0, n, Charsets.ISO_8859_1)
                    if (text.contains(GROUP_CHANGE_TYPE)) {
                        onChannelsChanged(id)
                        tail = ""
                    } else {
                        tail = text.takeLast(GROUP_CHANGE_TYPE.length - 1)
                    }
                }
                setState(server, TakStatus.DISCONNECTED, "Closed by server")
            } catch (e: EnrollmentException) {
                setState(server, TakStatus.FAILED, e.message)
            } catch (e: SSLException) {
                currentCoroutineContext().ensureActive()
                val mismatch = e.hostnameMismatch()
                if (mismatch != null) {
                    // Not a client-certificate problem, so this must not discard the cached enrollment.
                    setState(server, TakStatus.FAILED, "TLS: ${mismatch.message}")
                } else {
                    sslFailure = true
                    setState(server, TakStatus.FAILED, "TLS: ${e.message ?: "handshake failed"}")
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                setState(server, TakStatus.FAILED, e.message ?: e.javaClass.simpleName)
            } finally {
                c.output = null
                c.credentials = null
                runCatching { c.socket?.close() }
                c.socket = null
                withContext(NonCancellable) { publishEndpoint() }
            }
            attempt++
            // Same as CIV: a repeatedly failing TLS session discards the cached enrollment so it is redone.
            if (sslFailure && attempt == 3) TakCertificates.deleteCached(context, server)
            delay(backoffMillis(attempt))
        }
    }

    private fun setState(server: TakServerConfig, status: TakStatus, message: String? = null) {
        TrackerState.takServers.update { it + (server.id to TakServerState(status, message)) }
    }

    private fun publishEndpoint() = TrackerState.setEndpoint(Endpoint.TAK_SERVER, anyConnected)

    companion object {
        private const val TAG = "TakServerManager"
        private const val GROUP_CHANGE_TYPE = "t-x-g-c"

        /** Reconnect delay: 5 s doubling per failed attempt, capped at 5 minutes. */
        internal fun backoffMillis(attempt: Int): Long =
            (5_000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(300_000L)
    }
}
