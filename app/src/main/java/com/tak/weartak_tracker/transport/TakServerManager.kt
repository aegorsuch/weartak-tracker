package com.tak.weartak_tracker.transport

import android.content.Context
import com.tak.weartak_tracker.diagnostics.AppLog as Log
import com.tak.weartak_tracker.cot.CotEventFramer
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** Parse inbound events only while this returns true; they go to [onInboundEvent]. */
    private val inboundEnabled: () -> Boolean = { false },
    private val onInboundEvent: (serverId: String, xml: String) -> Unit = { _, _ -> },
) {
    private class Connection(@Volatile var server: TakServerConfig) {
        var job: Job? = null
        /** Plain socket while connecting, then the TLS socket layered on it; closing either aborts I/O. */
        @Volatile var socket: Socket? = null
        @Volatile var output: OutputStream? = null
        @Volatile var readyGeneration = 0L
        @Volatile var credentials: TakCertificates.Credentials? = null
        var channelClient: TakChannelApi? = null
        @Volatile var enrollmentInProgress = false
        val lock = Any()
    }

    private val connections = ConcurrentHashMap<String, Connection>()
    @Volatile private var servers: List<TakServerConfig> = emptyList()
    private val identitySockets = ConcurrentHashMap<String, Socket>()
    private val credentialMutex = Mutex()
    private val readyGeneration = java.util.concurrent.atomic.AtomicLong()

    val anyConnected: Boolean get() = connections.values.any { it.output != null }
    val readyGenerations: Map<String, Long> get() = connections.entries
        .filter { it.value.output != null && it.value.readyGeneration > 0 }
        .associate { it.key to it.value.readyGeneration }

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
            connections.remove(id)?.let { close(it, "server editor closed or settings changed") }
        }
        identitySockets.keys.filter { id -> wanted[id] == null }.forEach { id ->
            identitySockets.remove(id)?.let(::closeIdentitySocket)
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
        connections.values.forEach { close(it, "network changed") }
        connections.clear()
        current.forEach(::start)
        publishEndpoint()
    }

    /**
     * Reconnects only [serverId] immediately, bypassing its reconnect backoff and clearing any previous
     * failure. Returns false (leaving state untouched) unless it is a configured, enabled, non-duplicate
     * server from the last [update].
     */
    fun retry(serverId: String): Boolean {
        val index = servers.indexOfFirst { it.id == serverId }
        if (index < 0) return false
        val server = servers[index]
        if (!isConnectable(server)) return false
        // Same rule as update(): the first enabled server for an address:port wins.
        if (servers.take(index).any { isConnectable(it) && it.endpointKey == server.endpointKey }) return false
        connections.remove(serverId)?.let { close(it, "user requested retry") }
        // Synchronous, so the UI sees the fresh attempt before the coroutine runs; the superseded run can
        // no longer publish because it is not the current connection (see setState(Connection, ...)).
        TrackerState.takServers.update { it + (serverId to TakServerState(TakStatus.CONNECTING)) }
        start(server)
        publishEndpoint()
        return true
    }

    private fun isConnectable(server: TakServerConfig) = server.enabled && server.address.isNotBlank()

    fun stop() {
        identitySockets.values.forEach(::closeIdentitySocket)
        identitySockets.clear()
        connections.values.forEach { close(it, "service stopped after app backgrounding or shutdown") }
        connections.clear()
        TrackerState.takServers.value = emptyMap()
        publishEndpoint()
    }

    /** Writes [xml] to every connected server; returns how many accepted it. */
    suspend fun send(xml: String): Int = sendWithGenerations(xml).size

    internal suspend fun sendWithGenerations(xml: String): Map<String, Long> = withContext(Dispatchers.IO) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val sent = mutableMapOf<String, Long>()
        for ((id, c) in connections) {
            val out = c.output ?: continue
            val generation = c.readyGeneration
            try {
                synchronized(c.lock) {
                    out.write(bytes)
                    out.flush()
                }
                sent[id] = generation
            } catch (e: Exception) {
                Log.w(TAG, "Write to ${c.server.endpointKey} failed", e)
                runCatching { c.socket?.close() }
            }
        }
        sent
    }

    suspend fun sendTo(serverId: String, xml: String): Int = withContext(Dispatchers.IO) {
        val connection = connections[serverId] ?: return@withContext 0
        val output = connection.output ?: return@withContext 0
        try {
            synchronized(connection.lock) {
                output.write(xml.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            1
        } catch (error: java.io.IOException) {
            Log.w(TAG, "Command reply write failed (${error.javaClass.simpleName})")
            runCatching { connection.socket?.close() }
            0
        }
    }

    /** Channel API client for a connected server, or null when it isn't connected. */
    internal fun channelApi(serverId: String): TakChannelApi? {
        val c = connections[serverId] ?: return null
        return synchronized(c.lock) {
            if (c.output == null || connections[serverId] !== c) return@synchronized null
            c.channelClient ?: run {
                val creds = c.credentials ?: return@synchronized null
                val server = c.server
                val host = server.address.trim()
                val discover = if (server.apiTlsName.isBlank()) { name: String ->
                    Log.i(TAG, "Discovered API TLS name $name for $host")
                    onTlsNameDiscovered(server, true, name)
                } else null
                TakChannelApi(host, TakCertificates.socketFactory(creds, host, server.apiTlsName, discover))
                    .also { c.channelClient = it }
            }
        }
    }

    private fun start(server: TakServerConfig, ready: Pair<SSLSocket, TakCertificates.Credentials>? = null) {
        val c = Connection(server)
        if (ready != null) {
            c.socket = ready.first
            c.output = ready.first.outputStream
            c.credentials = ready.second
        }
        connections[server.id] = c
        c.job = scope.launch(Dispatchers.IO) { run(c, ready) }
    }

    /**
     * Keep the old socket/credential live until staged credentials pass a real hostname-checked TLS
     * handshake. The verified socket becomes the connection; there is no second-handshake gap.
     */
    internal suspend fun installIdentity(
        server: TakServerConfig,
        credentials: TakCertificates.Credentials,
        commit: suspend () -> Boolean,
        rollback: () -> Unit,
    ): Boolean = credentialMutex.withLock {
        if (servers.none { it.enabled && it.id == server.id && it.connectionKey == server.connectionKey } ||
            !isConnectable(server) || servers.takeWhile { it.id != server.id }.any {
                isConnectable(it) && it.endpointKey == server.endpointKey
            }
        ) return@withLock false
        var candidate: SSLSocket? = null
        var adopted = false
        val retainedCredentials = credentials.copy(password = credentials.password.copyOf())
        try {
            withContext(Dispatchers.IO) {
                val plain = Socket()
                try {
                    identitySockets[server.id] = plain
                    if (servers.none { it.enabled && it.id == server.id && it.connectionKey == server.connectionKey }) {
                        throw java.io.IOException("TAK Server is disabled or its settings changed")
                    }
                    plain.connect(InetSocketAddress(server.address.trim(), server.port), 15_000)
                    val tls = TakCertificates.socketFactory(credentials, server.address.trim(), server.tlsName)
                        .createSocket(plain, server.tlsName.ifBlank { server.address.trim() }, server.port, true) as SSLSocket
                    candidate = tls
                    tls.soTimeout = 15_000
                    tls.startHandshake()
                    tls.soTimeout = 0
                } catch (error: Exception) {
                    plain.close()
                    throw error
                }
            }
            val socket = requireNotNull(candidate)
            commitVerifiedIdentity(
                stillValid = { servers.any { it.enabled && it.id == server.id && it.connectionKey == server.connectionKey } },
                commit = commit,
                activate = {
                    val old = connections[server.id]
                    start(server, socket to retainedCredentials)
                    adopted = true
                    old?.let { close(it, "client identity replaced after verified TLS handshake") }
                },
                rollback = rollback,
            )
        } finally {
            identitySockets.remove(server.id)
            if (!adopted) {
                try {
                    candidate?.close()
                } finally {
                    retainedCredentials.password.fill('\u0000')
                }
            }
        }
    }

    private fun close(c: Connection, reason: String) {
        if (c.enrollmentInProgress) {
            Log.i(TAG, "Enrollment for ${c.server.endpointKey} cancelled because $reason")
        }

        c.job?.cancel()
        c.output = null
        c.credentials = null
        runCatching { c.socket?.close() }
        c.socket = null
        synchronized(c.lock) {
            c.channelClient?.close()
            c.channelClient = null
        }
    }

    private fun closeIdentitySocket(socket: Socket) {
        try {
            socket.close()
        } catch (error: java.io.IOException) {
            Log.w(TAG, "Unable to close pending identity connection", error)
        }
    }

    private suspend fun run(c: Connection, ready: Pair<SSLSocket, TakCertificates.Credentials>? = null) {
        val id = c.server.id
        var attempt = 0
        var initial = ready
        while (currentCoroutineContext().isActive && connections[id] === c) {
            val server = c.server
            var sslFailure = false
            try {
                setState(c, TakStatus.CONNECTING)
                val prepared = initial
                initial = null
                val creds = prepared?.second ?: TakCertificates.obtain(context, server, deviceUid) {
                    c.enrollmentInProgress = true
                    setState(c, TakStatus.ENROLLING)
                }
                c.enrollmentInProgress = false
                currentCoroutineContext().ensureActive()
                setState(c, TakStatus.CONNECTING)
                val host = server.address.trim()
                val socket = prepared?.first ?: run {
                    val plain = Socket()
                    c.socket = plain
                    currentCoroutineContext().ensureActive()
                    plain.keepAlive = true
                    plain.tcpNoDelay = true
                    plain.connect(InetSocketAddress(host, server.port), 15_000)
                    // Keep the established hostname/CA-scoped TLS discovery rules on reconnect.
                    val sniHost = server.tlsName.ifBlank { host }
                    val discover = if (server.tlsName.isBlank()) { name: String ->
                        Log.i(TAG, "Discovered TLS name $name for ${server.endpointKey}")
                        c.server = c.server.copy(tlsName = name)
                        onTlsNameDiscovered(server, false, name)
                    } else null
                    val factory = TakCertificates.socketFactory(creds, host, server.tlsName, discover)
                    val socket = factory.createSocket(plain, sniHost, server.port, true) as SSLSocket
                    c.socket = socket
                    currentCoroutineContext().ensureActive()
                    socket.startHandshake()
                    socket
                }
                currentCoroutineContext().ensureActive()
                c.credentials = creds
                c.readyGeneration = readyGeneration.incrementAndGet()
                c.output = socket.outputStream
                attempt = 0
                setState(c, TakStatus.CONNECTED, "Certificate: ${creds.source}")
                publishEndpoint()
                onConnected()
                onServerConnected(id)
                val input = socket.inputStream
                val buf = ByteArray(8192)
                var tail = ""
                val framer = CotEventFramer()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // Inbound CoT is otherwise unused; only watch for the server's channel-change notice.
                    if (inboundEnabled()) {
                        framer.feed(String(buf, 0, n, Charsets.ISO_8859_1)).forEach { raw ->
                            onInboundEvent(id, String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
                        }
                    } else framer.clear()
                    val text = tail + String(buf, 0, n, Charsets.ISO_8859_1)
                    if (text.contains(GROUP_CHANGE_TYPE)) {
                        onChannelsChanged(id)
                        tail = ""
                    } else {
                        tail = text.takeLast(GROUP_CHANGE_TYPE.length - 1)
                    }
                }
                setState(c, TakStatus.DISCONNECTED, "Closed by server")
            } catch (e: CancellationException) {
                throw e
            } catch (e: EnrollmentException) {
                c.enrollmentInProgress = false
                currentCoroutineContext().ensureActive()
                Log.w(
                    TAG,
                    "Enrollment failed for ${server.address.trim()}:${TakCertificates.ENROLLMENT_PORT} " +
                        "during ${e.failure.stage.label}: ${e.failure.displayMessage()}",
                    e,
                )
                setState(c, TakStatus.FAILED, e.failure.displayMessage(context))
            } catch (e: SSLException) {
                c.enrollmentInProgress = false
                currentCoroutineContext().ensureActive()
                val mismatch = e.hostnameMismatch()
                if (mismatch != null) {
                    // Not a client-certificate problem, so this must not discard the cached enrollment.
                    setState(c, TakStatus.FAILED, "TLS: ${mismatch.message}")
                } else {
                    sslFailure = true
                    Log.w(TAG, "TLS failure for ${server.endpointKey}", e)
                    setState(c, TakStatus.FAILED, connectionFailure(e))
                }
            } catch (e: Exception) {
                c.enrollmentInProgress = false
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Connection failure for ${server.endpointKey}", e)
                setState(c, TakStatus.FAILED, connectionFailure(e))
            } finally {
                c.output = null
                c.credentials = null
                runCatching { c.socket?.close() }
                c.socket = null
                synchronized(c.lock) {
                    c.channelClient?.close()
                    c.channelClient = null
                }
                withContext(NonCancellable) { publishEndpoint() }
            }
            attempt++
            // Same as CIV: a repeatedly failing TLS session discards the cached enrollment so it is redone.
            if (sslFailure && attempt == 3) TakCertificates.deleteCached(context, server)
            delay(backoffMillis(attempt))
        }
    }

    private fun connectionFailure(e: Exception) =
        EnrollmentFailure.from(EnrollmentStage.CONNECTION, e).displayMessage(context)

    /**
     * Publishes [status] only while [c] is still the server's current connection; a run replaced by
     * retry/update/stop must not overwrite the newer attempt's state from its catch/finally path.
     * The check runs inside the atomic update so a concurrent replacement forces a re-check.
     */
    private fun setState(c: Connection, status: TakStatus, message: String? = null) {
        val id = c.server.id
        TrackerState.takServers.update { states ->
            if (connections[id] === c) states + (id to TakServerState(status, message)) else states
        }
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
