package com.tak.weartak_tracker.command

import com.tak.weartak_tracker.data.ROLE_CATEGORIES
import com.tak.weartak_tracker.data.TEAMS
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where validated identity values are written. Any argument left null is unchanged. */
fun interface IdentityStore {
    suspend fun apply(callsign: String?, team: String?, role: String?)
}

/**
 * `setcallsign`, `setteam`, `setrole` and `setprofile`. Every value is validated before anything is
 * stored, so one bad value changes nothing.
 */
class IdentityHandler(
    private val keys: Set<String>,
    private val store: IdentityStore,
    private val teams: List<String> = TEAMS,
    private val roles: List<String> = ROLE_CATEGORIES.flatMap { it.roles },
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val args = command.args
        if (args.isEmpty()) {
            return ChatCommandResult(false, "${command.name} needs ${keys.sorted().joinToString(", ") { "$it=<value>" }}")
        }
        val unknown = args.keys - keys
        if (unknown.isNotEmpty()) {
            return ChatCommandResult(false, "${command.name} does not take ${unknown.sorted().joinToString(", ")}")
        }
        val callsign = args["callsign"]?.let { ChatCommandValidation.callsign(it) ?: return invalid("callsign") }
        val team = args["team"]?.let { v -> teams.firstOrNull { it.equals(v.trim(), true) } ?: return invalid("team") }
        val role = args["role"]?.let { v -> roles.firstOrNull { it.equals(v.trim(), true) } ?: return invalid("role") }
        store.apply(callsign, team, role)
        return ChatCommandResult(true, "Updated ${args.keys.sorted().joinToString(", ")}")
    }

    private fun invalid(key: String) = ChatCommandResult(false, "Invalid $key; nothing changed")

    companion object {
        val COMMAND_KEYS: Map<String, Set<String>> = mapOf(
            "setcallsign" to setOf("callsign"),
            "setteam" to setOf("team"),
            "setrole" to setOf("role"),
            "setprofile" to setOf("callsign", "team", "role"),
        )
    }
}

data class PresetServer(val name: String, val address: String, val port: Int, val username: String, val password: String)

data class ProfilePreset(val profile: Map<String, String>, val servers: List<PresetServer>)

object ProfilePresetParser {
    private val NAME = Regex("[a-z0-9][a-z0-9\\-]{0,31}")

    fun validName(name: String?): String? = name?.trim()?.lowercase()?.takeIf { NAME.matches(it) }

    /** Returns null for malformed JSON or any invalid server entry, so a bad preset applies nothing. */
    fun parse(json: String): ProfilePreset? = try {
        val root = JSONObject(json)
        val profile = root.optJSONObject("profile")?.let { o ->
            o.keys().asSequence().associateWith { o.optString(it) }
        }.orEmpty()
        val servers = root.optJSONArray("servers")?.let(::servers) ?: emptyList()
        if (servers.any { it == null }) null else ProfilePreset(profile, servers.filterNotNull())
    } catch (error: JSONException) {
        null
    }

    private fun servers(array: JSONArray): List<PresetServer?> = (0 until array.length()).map { i ->
        val o = array.optJSONObject(i) ?: return@map null
        val name = ChatCommandValidation.serverName(o.optString("name"))
        val address = ChatCommandValidation.serverAddress(o.optString("url"))
        val port = ChatCommandValidation.port(if (o.has("port")) o.optInt("port").toString() else null)
        val username = o.optString("username")
        val password = o.optString("password")
        if (name == null || address == null || port == null || username.isBlank() || password.isEmpty()) null
        else PresetServer(name, address, port, username, password)
    }
}

fun interface PresetSource {
    suspend fun load(name: String): String?
}

fun interface PresetServerSink {
    /** Adds the servers that are not already configured and returns how many were added. */
    suspend fun add(servers: List<PresetServer>): Int
}

/**
 * `#weartaktrackercommandloadprofile name=odin-weartak` applies a preset stored on the device. Credentials
 * come from that file, never from chat, and nothing from it is logged or sent back.
 */
class LoadProfileHandler(
    private val source: PresetSource,
    private val identity: IdentityHandler,
    private val serverSink: PresetServerSink,
    private val networkLocked: suspend () -> Boolean,
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        if (command.args.keys != setOf("name")) return ChatCommandResult(false, "loadprofile needs name=<preset>")
        val name = ProfilePresetParser.validName(command.args["name"])
            ?: return ChatCommandResult(false, "Invalid preset name")
        val preset = source.load(name)?.let(ProfilePresetParser::parse)
            ?: return ChatCommandResult(false, "Preset $name not found or invalid on this device")
        if (preset.servers.isNotEmpty() && networkLocked()) {
            return ChatCommandResult(false, "Network settings are locked; nothing changed")
        }
        var profileSummary = "no profile"
        if (preset.profile.isNotEmpty()) {
            val result = identity.execute(ChatCommand("setprofile", preset.profile.mapKeys { it.key.lowercase() }), request)
            if (!result.ok) return ChatCommandResult(false, "Preset $name profile rejected: ${result.message}")
            profileSummary = "profile applied"
        }
        val added = if (preset.servers.isEmpty()) 0 else serverSink.add(preset.servers)
        return ChatCommandResult(true, "Loaded $name: $profileSummary, $added of ${preset.servers.size} servers added")
    }
}
