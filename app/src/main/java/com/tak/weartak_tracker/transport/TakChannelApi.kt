package com.tak.weartak_tracker.transport

import android.util.Log
import com.tak.weartak_tracker.data.TAK_API_PORT
import com.tak.weartak_tracker.data.TakChannel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

class TakChannelsUnsupportedException : Exception("Channels unsupported by this TAK Server")

/** The raw `data` array (kept so updates PUT back every field) plus the parsed channels. */
internal class TakChannelSnapshot(val payload: JSONArray, val channels: List<TakChannel>)

/** TAK Server group-API JSON handling, mirroring WearTAK-CIV's TakChannelJson. */
internal object TakChannelJson {
    fun parseSupport(body: String): Boolean {
        val data = root(body).opt("data")
        return data as? Boolean ?: throw JSONException("Channel support response has no Boolean data field")
    }

    fun parseGroups(body: String): TakChannelSnapshot {
        val data = root(body).optJSONArray("data") ?: throw JSONException("Channel response has no data array")
        return TakChannelSnapshot(data, channels(data))
    }

    /** Sets `active` on every group entry (IN and OUT) with [bitPosition]. */
    fun withActive(payload: JSONArray, bitPosition: Int, active: Boolean): TakChannelSnapshot {
        val updated = JSONArray(payload.toString())
        var found = false
        for (i in 0 until updated.length()) {
            val group = updated.optJSONObject(i) ?: continue
            if (group.bitPosition() == bitPosition) {
                group.put("active", active)
                found = true
            }
        }
        if (!found) throw JSONException("Channel bit position $bitPosition was not returned by TAK Server")
        return TakChannelSnapshot(updated, channels(updated))
    }

    private fun channels(data: JSONArray): List<TakChannel> = (0 until data.length())
        .mapNotNull { i ->
            val g = data.optJSONObject(i) ?: return@mapNotNull null
            val name = g.optString("name").trim()
            val bit = g.bitPosition() ?: return@mapNotNull null
            if (name.isEmpty()) null
            else TakChannel(name, g.optString("direction"), g.optBoolean("active", false), bit)
        }
        .distinctBy { it.bitPosition }

    private fun JSONObject.bitPosition(): Int? = when (val v = opt("bitpos")) {
        is Int -> v
        is Long -> v.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun root(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw JSONException("Unable to parse TAK channel response")
    }
}

/** mTLS client for TAK Server's group API on port 8443. */
internal class TakChannelApi(private val address: String, private val socketFactory: SSLSocketFactory) {
    private val base = "https://${hostForUrl(address.trim())}:$TAK_API_PORT/Marti/api/groups"

    fun load(checkSupport: Boolean): TakChannelSnapshot {
        if (checkSupport && !TakChannelJson.parseSupport(request("GET", "$base/groupCacheEnabled"))) {
            throw TakChannelsUnsupportedException()
        }
        return TakChannelJson.parseGroups(request("GET", "$base/all?useCache=true"))
    }

    /** Re-reads the full group list, flips one bit position, and PUTs the whole list back. */
    fun setActive(bitPosition: Int, active: Boolean, clientUid: String): TakChannelSnapshot {
        val updated = TakChannelJson.withActive(load(checkSupport = false).payload, bitPosition, active)
        val uid = URLEncoder.encode(clientUid, "UTF-8")
        request("PUT", "$base/active?clientUid=$uid", updated.payload.toString())
        return updated
    }

    private fun request(method: String, url: String, body: String? = null): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.sslSocketFactory = socketFactory
            // The socket factory's trust manager already checks the server identity during the handshake
            // (including IP addresses and discovered TLS names), before the client certificate is sent.
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "*/*")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val path = URL(url).path
            Log.i(TAG, "$method $path -> $code")
            if (code !in 200..299) throw java.io.IOException("TAK channel request failed: $code $method $path")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "TakChannelApi"
        fun hostForUrl(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    }
}
