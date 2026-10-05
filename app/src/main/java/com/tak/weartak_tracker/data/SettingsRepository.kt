package com.tak.weartak_tracker.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings.Secure
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.trackerStore: DataStore<Preferences> by preferencesDataStore(name = "tracker")

const val DEFAULT_CONSTANT_REPORTING_INTERVAL = 60
const val DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL = 10
const val DEFAULT_ON_FOOT_REPORTING_INTERVAL = 60
const val DEFAULT_VEHICLE_REPORTING_INTERVAL = 60
const val DEFAULT_STATIONARY_REPORTING_INTERVAL = 3600
const val DEFAULT_MULTICAST_ADDRESS = "239.2.3.1"
const val DEFAULT_MULTICAST_PORT = 6969
const val DEFAULT_TAK_PORT = 8089

/** Same output protocol options as WearTAK-CIV (UDP only). */
val MULTICAST_PROTOCOLS = listOf("UDP")
const val DEFAULT_MULTICAST_PROTOCOL = "UDP"

val TEAMS = listOf(
    "White", "Yellow", "Orange", "Magenta", "Red", "Maroon", "Purple",
    "Dark Blue", "Blue", "Cyan", "Teal", "Green", "Dark Green", "Brown",
)

data class RoleCategory(val category: String, val roles: List<String>)

/** Same default role categories as WearTAK-CIV (res/raw/roles_json_config). */
val ROLE_CATEGORIES = listOf(
    RoleCategory("MIL", listOf("Forward Observer", "HQ", "K9", "Medic", "RTO", "Sniper", "Team Lead", "Team Member")),
    RoleCategory(
        "LEO",
        listOf(
            "Armed Surveillance", "Assistant Team Leader", "Aviation", "Bomb Tech", "Command Post",
            "Critical Response", "Hazards", "Negotiator", "Surveillance", "Tactical Communicator", "TOC",
        ),
    ),
    RoleCategory(
        "FES",
        listOf(
            "Assistant Chief", "Battalion Chief", "Captain", "Deputy Chief", "District Chief", "Engineer",
            "Fire Chief", "Firefighter", "Lieutenant", "Safety Officer",
        ),
    ),
)

const val DEFAULT_TEAM = "Orange"
const val DEFAULT_ROLE = "Team Member"

data class TakServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val address: String = "",
    val port: Int = DEFAULT_TAK_PORT,
    val enabled: Boolean = true,
    val username: String = "",
    val password: String = "",
) {
    val endpointKey: String get() = "${address.trim().lowercase()}:$port"
}

data class TrackerConfig(
    val callsign: String = "",
    val team: String = DEFAULT_TEAM,
    val role: String = DEFAULT_ROLE,
    val dynamicReporting: Boolean = true,
    val constantInterval: Int = DEFAULT_CONSTANT_REPORTING_INTERVAL,
    val alertingInterval: Int = DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL,
    val onFootInterval: Int = DEFAULT_ON_FOOT_REPORTING_INTERVAL,
    val vehicleInterval: Int = DEFAULT_VEHICLE_REPORTING_INTERVAL,
    val stationaryInterval: Int = DEFAULT_STATIONARY_REPORTING_INTERVAL,
    val multicastEnabled: Boolean = true,
    val multicastAddress: String = DEFAULT_MULTICAST_ADDRESS,
    val multicastPort: Int = DEFAULT_MULTICAST_PORT,
    val multicastProtocol: String = DEFAULT_MULTICAST_PROTOCOL,
    val sitxEnabled: Boolean = false,
    val sitxUrl: String = "",
    val sitxClientId: String = "",
    val sitxGroup: String = "",
    val servers: List<TakServerConfig> = emptyList(),
)

/** SITX OAuth device-flow state (persisted encrypted). */
data class SitxTokens(
    val refreshToken: String = "",
    val refreshExpiresAtEpochSec: Long = 0L,
    val baseUrl: String = "",
    val clientId: String = "",
)

class SettingsRepository(private val context: Context) {
    private val store = context.trackerStore

    private object K {
        val CALLSIGN = stringPreferencesKey("callsign")
        val TEAM = stringPreferencesKey("team")
        val ROLE = stringPreferencesKey("role")
        val DYNAMIC = booleanPreferencesKey("enable_dynamic_reporting_strategy")
        val CONSTANT = intPreferencesKey("constant_reporting_interval")
        val ALERTING = intPreferencesKey("while_alerting_reporting_interval")
        val ON_FOOT = intPreferencesKey("on_foot_reporting_interval")
        val VEHICLE = intPreferencesKey("vehicle_reporting_interval")
        val STATIONARY = intPreferencesKey("stationary_reporting_interval")
        val MC_ENABLED = booleanPreferencesKey("enable_multicast")
        val MC_ADDRESS = stringPreferencesKey("multicast_address")
        val MC_PORT = intPreferencesKey("multicast_port")
        val MC_PROTOCOL = stringPreferencesKey("multicast_protocol")
        val SITX_ENABLED = booleanPreferencesKey("sitx_enabled")
        val SITX_URL = stringPreferencesKey("sitx_url")
        val SITX_CLIENT_ID = stringPreferencesKey("sitx_client_id.enc")
        val SITX_GROUP = stringPreferencesKey("sitx_group.enc")
        val SITX_TOKENS = stringPreferencesKey("sitx_tokens.enc")
        val SERVERS = stringPreferencesKey("tak_servers.enc")
    }

    @SuppressLint("HardwareIds")
    val deviceUid: String =
        "WEAROS_" + (Secure.getString(context.contentResolver, Secure.ANDROID_ID) ?: UUID.randomUUID().toString())

    private val defaultCallsign = "WT-" + deviceUid.takeLast(4).uppercase()

    val config: Flow<TrackerConfig> = store.data.map { read(it) }
        .distinctUntilChanged()

    suspend fun current(): TrackerConfig = config.first()

    suspend fun update(transform: (TrackerConfig) -> TrackerConfig) {
        store.edit { p ->
            val old = read(p)
            val n = transform(old)
            if (n == old) return@edit
            p[K.CALLSIGN] = n.callsign.trim()
            p[K.TEAM] = n.team
            p[K.ROLE] = n.role
            p[K.DYNAMIC] = n.dynamicReporting
            p[K.CONSTANT] = n.constantInterval.coerceAtLeast(1)
            p[K.ALERTING] = n.alertingInterval.coerceAtLeast(1)
            p[K.ON_FOOT] = n.onFootInterval.coerceAtLeast(1)
            p[K.VEHICLE] = n.vehicleInterval.coerceAtLeast(1)
            p[K.STATIONARY] = n.stationaryInterval.coerceAtLeast(1)
            p[K.MC_ENABLED] = n.multicastEnabled
            p[K.MC_ADDRESS] = n.multicastAddress.trim()
            p[K.MC_PORT] = n.multicastPort
            p[K.MC_PROTOCOL] = n.multicastProtocol
            p[K.SITX_ENABLED] = n.sitxEnabled
            p[K.SITX_URL] = n.sitxUrl.trim()
            if (n.sitxClientId != old.sitxClientId) p[K.SITX_CLIENT_ID] = SecretBox.encrypt(n.sitxClientId.trim())
            if (n.sitxGroup != old.sitxGroup) p[K.SITX_GROUP] = SecretBox.encrypt(n.sitxGroup)
            if (n.servers != old.servers) p[K.SERVERS] = SecretBox.encrypt(serializeServers(n.servers))
        }
    }

    suspend fun sitxTokens(): SitxTokens {
        val raw = SecretBox.decrypt(store.data.first()[K.SITX_TOKENS]) ?: return SitxTokens()
        return runCatching {
            val o = JSONObject(raw)
            SitxTokens(
                refreshToken = o.optString("refreshToken"),
                refreshExpiresAtEpochSec = o.optLong("refreshExpiresAtEpochSec"),
                baseUrl = o.optString("baseUrl"),
                clientId = o.optString("clientId"),
            )
        }.getOrDefault(SitxTokens())
    }

    suspend fun setSitxTokens(tokens: SitxTokens) {
        val json = JSONObject()
            .put("refreshToken", tokens.refreshToken)
            .put("refreshExpiresAtEpochSec", tokens.refreshExpiresAtEpochSec)
            .put("baseUrl", tokens.baseUrl)
            .put("clientId", tokens.clientId)
            .toString()
        store.edit { it[K.SITX_TOKENS] = SecretBox.encrypt(json) }
    }

    private fun read(p: Preferences): TrackerConfig =
        TrackerConfig(
            callsign = p[K.CALLSIGN]?.takeIf { it.isNotBlank() } ?: defaultCallsign,
            team = p[K.TEAM] ?: DEFAULT_TEAM,
            role = p[K.ROLE] ?: DEFAULT_ROLE,
            dynamicReporting = p[K.DYNAMIC] ?: true,
            constantInterval = p[K.CONSTANT] ?: DEFAULT_CONSTANT_REPORTING_INTERVAL,
            alertingInterval = p[K.ALERTING] ?: DEFAULT_WHILE_ALERTING_REPORTING_INTERVAL,
            onFootInterval = p[K.ON_FOOT] ?: DEFAULT_ON_FOOT_REPORTING_INTERVAL,
            vehicleInterval = p[K.VEHICLE] ?: DEFAULT_VEHICLE_REPORTING_INTERVAL,
            stationaryInterval = p[K.STATIONARY] ?: DEFAULT_STATIONARY_REPORTING_INTERVAL,
            multicastEnabled = p[K.MC_ENABLED] ?: true,
            multicastAddress = p[K.MC_ADDRESS] ?: DEFAULT_MULTICAST_ADDRESS,
            multicastPort = p[K.MC_PORT] ?: DEFAULT_MULTICAST_PORT,
            multicastProtocol = p[K.MC_PROTOCOL]?.takeIf { it in MULTICAST_PROTOCOLS } ?: DEFAULT_MULTICAST_PROTOCOL,
            sitxEnabled = p[K.SITX_ENABLED] ?: false,
            sitxUrl = p[K.SITX_URL] ?: "",
            sitxClientId = SecretBox.decrypt(p[K.SITX_CLIENT_ID]) ?: "",
            sitxGroup = SecretBox.decrypt(p[K.SITX_GROUP]) ?: "",
            servers = parseServers(SecretBox.decrypt(p[K.SERVERS])),
        )

    private fun parseServers(raw: String?): List<TakServerConfig> {
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
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeServers(servers: List<TakServerConfig>): String = JSONArray().apply {
        servers.forEach { s ->
            put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("address", s.address.trim()).put("port", s.port)
                    .put("enabled", s.enabled).put("username", s.username).put("password", s.password),
            )
        }
    }.toString()
}
