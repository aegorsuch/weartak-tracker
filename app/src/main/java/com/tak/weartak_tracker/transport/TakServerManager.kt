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
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Maintains one TLS streaming connection per enabled TAK Server (send-only; inbound data is discarded). */
class TakServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deviceUid: String,
    private val onConnected: () -> Unit,
) {
    private class Connection(val server: TakServerConfig) {
        var job: Job? = null
        @Volatile var socket: SSLSocket? = null
        @Volatile var output: OutputStream? = null
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
        connections.keys.filter { id -> wanted[id] != connections[id]?.server }.forEach { id ->
            connections.remove(id)?.let(::close)
        }
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

    private fun start(server: TakServerConfig) {
        val c = Connection(server)
        connections[server.id] = c
        c.job = scope.launch(Dispatchers.IO) { run(c) }
    }

    private fun close(c: Connection) {
        c.job?.cancel()
        c.output = null
        runCatching { c.socket?.close() }
        c.socket = null
    }

    private suspend fun run(c: Connection) {
        val server = c.server
        var attempt = 0
        while (currentCoroutineContext().isActive && connections[server.id] === c) {
            var sslFailure = false
            try {
                setState(server, TakStatus.CONNECTING)
                val creds = TakCertificates.obtain(context, server, deviceUid) {
                    setState(server, TakStatus.ENROLLING)
                }
                setState(server, TakStatus.CONNECTING)
                val socket = TakCertificates.socketFactory(creds).createSocket() as SSLSocket
                c.socket = socket
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(server.address.trim(), server.port), 15_000)
                socket.startHandshake()
                c.output = socket.outputStream
                attempt = 0
                setState(server, TakStatus.CONNECTED, "Certificate: ${creds.source}")
                publishEndpoint()
                onConnected()
                val input = socket.inputStream
                val buf = ByteArray(8192)
                while (input.read(buf) >= 0) { /* inbound CoT is not used by the tracker */ }
                setState(server, TakStatus.DISCONNECTED, "Closed by server")
            } catch (e: EnrollmentException) {
                setState(server, TakStatus.FAILED, e.message)
            } catch (e: SSLException) {
                currentCoroutineContext().ensureActive()
                sslFailure = true
                setState(server, TakStatus.FAILED, "TLS: ${e.message ?: "handshake failed"}")
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                setState(server, TakStatus.FAILED, e.message ?: e.javaClass.simpleName)
            } finally {
                c.output = null
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

    private fun backoffMillis(attempt: Int): Long =
        (5_000L shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(300_000L)

    private fun setState(server: TakServerConfig, status: TakStatus, message: String? = null) {
        TrackerState.takServers.update { it + (server.id to TakServerState(status, message)) }
    }

    private fun publishEndpoint() = TrackerState.setEndpoint(Endpoint.TAK_SERVER, anyConnected)

    companion object { private const val TAG = "TakServerManager" }
}
