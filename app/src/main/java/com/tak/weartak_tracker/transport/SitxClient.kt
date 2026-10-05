package com.tak.weartak_tracker.transport

import android.util.Log
import com.tak.weartak_tracker.data.Endpoint
import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.SitxGroup
import com.tak.weartak_tracker.data.SitxState
import com.tak.weartak_tracker.data.SitxTokens
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.TrackerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class SitxAuthException(message: String) : Exception(message)

/** SITX device-flow login + WebSocket CoT transport, ported from WearTAK-CIV's SitxTakService. */
class SitxClient(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val deviceUid: String,
    private val onConnected: () -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketOpen = false
    private var settings: Triple<String, String, String>? = null
    private var callsign: String = ""
    private var reconnectAttempt = 0

    val connected: Boolean get() = socketOpen

    fun update(config: TrackerConfig) {
        callsign = config.callsign
        val next = if (config.sitxEnabled) Triple(baseUrl(config.sitxUrl), config.sitxClientId.trim(), config.sitxGroup) else null
        if (next == settings) return
        val old = settings
        settings = next
        if (next == null) {
            stop()
            TrackerState.sitx.value = SitxState.Disabled
            return
        }
        // A group change alone does not require a new login.
        if (old != null && old.first == next.first && old.second == next.second) launch { connectSelectedGroup() }
        else launch { start() }
    }

    fun reauthorize() = launch {
        repo.setSitxTokens(SitxTokens())
        start()
    }

    fun refreshGroups() = launch { fetchGroupsAndConnect() }

    fun onNetworkChanged() {
        if (settings == null || TrackerState.sitx.value is SitxState.AwaitingUser) return
        launch {
            closeSocket()
            delay(5_000)
            start()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeSocket()
    }

    fun send(xml: String): Int {
        val ws = socket ?: return 0
        return if (socketOpen && ws.send(xml)) 1 else 0
    }

    private fun launch(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "SITX error", e)
                    TrackerState.sitx.value = SitxState.Error(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private suspend fun start() {
        val (base, clientId, _) = settings ?: return
        closeSocket()
        if (base.isBlank() || clientId.isBlank()) {
            TrackerState.sitx.value = SitxState.Error("Set the SITX URL and client ID")
            return
        }
        val tokens = repo.sitxTokens()
        val valid = tokens.refreshToken.isNotBlank() &&
            tokens.refreshExpiresAtEpochSec > Instant.now().epochSecond + 30 &&
            tokens.baseUrl == base && tokens.clientId == clientId
        if (!valid) deviceGrant(base, clientId)
        fetchGroupsAndConnect()
    }

    private suspend fun deviceGrant(base: String, clientId: String) {
        TrackerState.sitx.value = SitxState.Idle
        val scopeValue = "role:org_user callsign:${callsign.replace(' ', '_')} device_name:$deviceUid device_id:$deviceUid"
        val body = JSONObject().put("scope", scopeValue).put("client_id", clientId).toString()
        val code = call(
            Request.Builder().url("$base/api/v1/device/authorization/code")
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON)).build(),
        )
        val deviceCode = code.getString("device_code")
        val verification = code.optString("verification_uri_complete").ifBlank { code.optString("verification_uri") }
        TrackerState.sitx.value = SitxState.AwaitingUser(code.optString("user_code"), verification)
        var interval = code.optLong("interval", 5).coerceAtLeast(1)
        val expiresAt = System.currentTimeMillis() + code.optLong("expires_in", 600) * 1000

        while (System.currentTimeMillis() < expiresAt) {
            delay(interval * 1000)
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
            val response = http.newCall(
                Request.Builder().url("$base/api/v1/device/authorization/token")
                    .header("Accept", "application/json").post(form).build(),
            ).execute()
            val json = response.use { r -> r.body?.string().orEmpty().let { runCatching { JSONObject(it) }.getOrNull() } }
            val refresh = json?.optString("refresh_token").orEmpty()
            if (refresh.isNotBlank()) {
                val expiresIn = json!!.optLong("expires_in", 0)
                repo.setSitxTokens(SitxTokens(refresh, Instant.now().epochSecond + expiresIn, base, clientId))
                TrackerState.sitx.value = SitxState.Authorized
                return
            }
            when (json?.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = (interval + 5).coerceAtMost(60)
                else -> throw IOException("Authorization failed: ${json?.optString("error")?.ifBlank { null } ?: "HTTP error"}")
            }
        }
        throw IOException("Authorization code expired")
    }

    private suspend fun fetchGroupsAndConnect() {
        val tokens = repo.sitxTokens()
        val base = settings?.first ?: return
        if (tokens.refreshToken.isBlank()) {
            start()
            return
        }
        val response = http.newCall(
            Request.Builder().url("$base/api/v1/tak_servers")
                .header("Accept", "application/json")
                .header("Authorization", bearer(tokens.refreshToken)).get().build(),
        ).execute()
        val text = response.use { r ->
            if (r.code == 401) {
                repo.setSitxTokens(SitxTokens())
                throw IOException("SITX session expired - re-authorize")
            }
            if (!r.isSuccessful) throw IOException("Group list failed: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val arr = JSONArray(text)
        val groups = (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { SitxGroup(it.optString("flow_tag"), it.optString("name").ifBlank { it.optString("flow_tag") }) }
            .filter { it.flowTag.isNotBlank() }
        TrackerState.sitxGroups.value = groups
        connectSelectedGroup()
    }

    private suspend fun connectSelectedGroup() {
        closeSocket()
        val (base, _, group) = settings ?: return
        val groups = TrackerState.sitxGroups.value
        if (groups.isEmpty()) {
            TrackerState.sitx.value = SitxState.NoGroups
            return
        }
        val selected = groups.firstOrNull { it.flowTag == group }
        if (selected == null) {
            TrackerState.sitx.value = SitxState.NeedsGroup
            return
        }
        TrackerState.sitx.value = SitxState.Connecting

        val tokens = repo.sitxTokens()
        val refreshed = call(
            Request.Builder().url("$base/api/v1/refresh/token")
                .header("Authorization", bearer(tokens.refreshToken))
                .post(ByteArray(0).toRequestBody(OCTET)).build(),
        )
        var refreshToken = refreshed.optString("refresh_token").ifBlank { tokens.refreshToken }
        var refreshExpiry = parseExpiry(refreshed.optString("refresh_token_expires_at")) ?: tokens.refreshExpiresAtEpochSec
        repo.setSitxTokens(tokens.copy(refreshToken = refreshToken, refreshExpiresAtEpochSec = refreshExpiry))

        val access = call(
            Request.Builder().url("$base/api/v1/access/token")
                .header("Authorization", bearer(refreshToken))
                .post(
                    FormBody.Builder().add("grant_type", "access")
                        .add("resource_type", "TAKSERVER")
                        .add("resource_key", selected.flowTag).build(),
                ).build(),
        )
        access.optString("refresh_token").takeIf { it.isNotBlank() }?.let { refreshToken = it }
        parseExpiry(access.optString("refresh_token_expires_at"))?.let { refreshExpiry = it }
        repo.setSitxTokens(tokens.copy(refreshToken = refreshToken, refreshExpiresAtEpochSec = refreshExpiry))

        val endPoint = access.optString("end_point")
        val accessToken = access.optString("access_token")
        if (endPoint.isBlank() || accessToken.isBlank()) throw IOException("SITX did not return a TAK endpoint")
        openSocket(endPoint, accessToken, selected)
    }

    private fun openSocket(endPoint: String, accessToken: String, group: SitxGroup) {
        val request = Request.Builder().url(endPoint).header("Authorization", bearer(accessToken)).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) return
                socketOpen = true
                reconnectAttempt = 0
                TrackerState.sitx.value = SitxState.Connected(group.name)
                TrackerState.setEndpoint(Endpoint.SITX, true)
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = Unit

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "Closed ($code)")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                lost(webSocket, t.message ?: "Connection failed")
        })
    }

    private fun lost(webSocket: WebSocket, reason: String) {
        if (socket !== webSocket) return
        socket = null
        socketOpen = false
        TrackerState.setEndpoint(Endpoint.SITX, false)
        TrackerState.sitx.value = SitxState.Error(reason)
        launch { reconnectLoop() }
    }

    private suspend fun reconnectLoop() {
        while (true) {
            delay((5_000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(60_000L))
            reconnectAttempt++
            try {
                connectSelectedGroup()
                return
            } catch (e: IOException) {
                TrackerState.sitx.value = SitxState.Error(e.message ?: "Reconnect failed")
            }
        }
    }

    private fun closeSocket() {
        val ws = socket
        socket = null
        socketOpen = false
        ws?.close(1000, null)
        TrackerState.setEndpoint(Endpoint.SITX, false)
    }

    private suspend fun call(request: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401) {
                repo.setSitxTokens(SitxTokens())
                throw SitxAuthException("SITX session expired - re-authorize")
            }
            if (r.code == 401) {
                repo.setSitxTokens(SitxTokens())
                throw SitxAuthException("SITX session expired - re-authorize")
            }
            if (!r.isSuccessful) throw IOException("${request.url.encodedPath} failed: HTTP ${r.code}")
            JSONObject(text)
        }
    }

    companion object {
        private const val TAG = "SitxClient"
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        private fun bearer(token: String) = "Bearer " + token

        fun baseUrl(input: String): String {
            val t = input.trim()
            if (t.isEmpty()) return ""
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t.trimEnd('/')
            return "https://" + t.lowercase().replace(" ", "") + ".sitx.io"
        }

        private fun parseExpiry(value: String): Long? {
            if (value.isBlank()) return null
            return runCatching { Instant.parse(value).epochSecond }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toEpochSecond() }.getOrNull()
        }
    }
}
