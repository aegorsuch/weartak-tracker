package com.tak.weartak_tracker.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** JSON form of the TAK Server list as stored (encrypted) in settings. */
internal object ServerListCodec {
    fun decode(raw: String?): List<TakServerConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TakServerConfig(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.optString("name"),
                    address = o.optString("address"),
                    port = o.optInt("port", DEFAULT_TAK_PORT),
                    enabled = o.optBoolean("enabled", true),
                    username = o.optString("username"),
                    password = o.optString("password"),
                    tlsName = o.optString("tlsName"),
                    apiTlsName = o.optString("apiTlsName"),
                )
            }.dedupeIds()
        }.getOrDefault(emptyList())
    }

    /** Ids key the server list UI and connections; repeated ids (older builds could save one twice) keep the latest entry. */
    fun List<TakServerConfig>.dedupeIds(): List<TakServerConfig> = asReversed().distinctBy { it.id }.asReversed()

    fun encode(servers: List<TakServerConfig>): String = JSONArray().apply {
        servers.forEach { s ->
            put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("address", s.address.trim()).put("port", s.port)
                    .put("enabled", s.enabled).put("username", s.username).put("password", s.password)
                    .put("tlsName", s.tlsName).put("apiTlsName", s.apiTlsName),
            )
        }
    }.toString()
}
