package com.tak.weartak_tracker.command

import com.tak.weartak_tracker.data.CustomAlerts
import com.tak.weartak_tracker.data.DittoPdlSource
import com.tak.weartak_tracker.service.DittoCommunityLicenseHelper
import com.tak.weartak_tracker.data.MEDICAL_SEX_OPTIONS
import com.tak.weartak_tracker.data.MEDICAL_USER_TYPE_OPTIONS
import com.tak.weartak_tracker.data.ROLE_CATEGORIES
import com.tak.weartak_tracker.data.TEAMS
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Year
import java.time.Instant

class DittoPdlUpdate(val source: DittoPdlSource, val customPdl: String?, val enabled: Boolean?) {
    override fun toString(): String = "DittoPdlUpdate(source=$source, enabled=$enabled)"
}

data class DittoPdlApplyResult(val enabled: Boolean, val applied: Boolean, val locked: Boolean = false)

fun interface DittoPdlStore {
    suspend fun apply(update: DittoPdlUpdate): DittoPdlApplyResult
}

class SetDittoPdlHandler(
    private val communityIdentity: () -> String,
    private val store: DittoPdlStore,
    private val networkLocked: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val args = command.args
        val community = args["source"] == "community"
        val custom = args.containsKey("pdl")
        if ((args.keys - setOf("source", "pdl", "enabled")).isNotEmpty() ||
            community == custom || (args.containsKey("source") && !community) ||
            (args.containsKey("enabled") && args["enabled"] !in setOf("true", "false"))
        ) return ChatCommandResult(false, "Ditto not changed: invalid arguments")
        if (networkLocked()) return ChatCommandResult(false, "Ditto not changed: settings locked")
        val pdl = (if (community) communityIdentity() else args.getValue("pdl")).trim()
        val license = try {
            require(pdl.length <= ChatCommandParser.MAX_PDL_LENGTH)
            DittoCommunityLicenseHelper.parse(pdl).also { require(it.expiresAtMillis > now()) }
        } catch (_: IllegalArgumentException) {
            return ChatCommandResult(false, "Ditto not changed: invalid or expired identity")
        }
        val result = store.apply(DittoPdlUpdate(
            if (community) DittoPdlSource.COMMUNITY else DittoPdlSource.CUSTOM,
            if (custom) pdl else null,
            args["enabled"]?.toBooleanStrict(),
        ))
        if (result.locked) return ChatCommandResult(false, "Ditto not changed: settings locked")
        if (!result.applied) return ChatCommandResult(false, "Ditto update failed")
        return ChatCommandResult(true, "Ditto updated: source=${if (community) "community" else "custom"} " +
            "app=${license.appId.take(8)} expiry=${Instant.ofEpochMilli(license.expiresAtMillis)} enabled=${result.enabled}")
    }
}

/** Validated profile values to store. Null fields are unchanged. */
data class ProfileUpdate(
    val callsign: String? = null,
    val team: String? = null,
    val role: String? = null,
    val heightFeet: Int? = null,
    val heightInches: Int? = null,
    val weightLbs: Int? = null,
    val birthYear: Int? = null,
    val sex: String? = null,
    val userType: String? = null,
    val pantsLengthInches: Int? = null,
    val waistInches: Int? = null,
)

/** Where validated profile values are written. */
fun interface ProfileStore {
    suspend fun apply(update: ProfileUpdate)
}

/**
 * Per-field commands (`setcallsign`, `setteam`, `setheight`, ...) and `setprofile`, which takes any
 * combination of fields. Every value is validated before anything is stored, so one bad value changes
 * nothing. Accepted values match the WearOS WearTAK chat commands and the tracker's settings screens.
 */
class ProfileHandler(
    private val keys: Set<String>,
    private val store: ProfileStore,
    private val teams: List<String> = TEAMS,
    private val roles: List<String> = ROLE_CATEGORIES.flatMap { it.roles },
    private val sexes: List<String> = MEDICAL_SEX_OPTIONS,
    private val userTypes: List<String> = MEDICAL_USER_TYPE_OPTIONS,
    private val currentYear: () -> Int = { Year.now().value },
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
        var update = ProfileUpdate()
        for ((key, value) in args) {
            update = parse(update, key, value) ?: return ChatCommandResult(false, "Invalid $key; nothing changed")
        }
        store.apply(update)
        return ChatCommandResult(true, "Updated ${args.keys.sorted().joinToString(", ")}")
    }

    private fun parse(u: ProfileUpdate, key: String, value: String): ProfileUpdate? = when (key) {
        "callsign" -> ChatCommandValidation.callsign(value)?.let { u.copy(callsign = it) }
        "team" -> match(value, teams)?.let { u.copy(team = it) }
        "role" -> match(value, roles)?.let { u.copy(role = it) }
        "height" -> ChatCommandValidation.height(value)?.let { u.copy(heightFeet = it.first, heightInches = it.second) }
        "weight" -> ChatCommandValidation.intIn(value, 1..1000)?.let { u.copy(weightLbs = it) }
        "birthyear" -> ChatCommandValidation.intIn(value, 1900..currentYear())?.let { u.copy(birthYear = it) }
        "sex" -> match(value, sexes)?.let { u.copy(sex = it) }
        "usertype" -> match(value, userTypes)?.let { u.copy(userType = it) }
        "pants" -> ChatCommandValidation.intIn(value, PANTS_RANGE)?.let { u.copy(pantsLengthInches = it) }
        "waist" -> ChatCommandValidation.intIn(value, WAIST_RANGE)?.let { u.copy(waistInches = it) }
        else -> null
    }

    private fun match(value: String, allowed: List<String>) = allowed.firstOrNull { it.equals(value.trim(), true) }

    companion object {
        val PANTS_RANGE = 20..50
        val WAIST_RANGE = 20..60
        val PROFILE_KEYS: Set<String> = linkedSetOf(
            "callsign", "team", "role", "height", "weight", "birthyear", "sex", "usertype", "pants", "waist",
        )

        /** Command name to the profile keys it takes. The long pants/waist names are aliases. */
        val COMMAND_KEYS: Map<String, Set<String>> = mapOf(
            "setcallsign" to setOf("callsign"),
            "setteam" to setOf("team"),
            "setrole" to setOf("role"),
            "setheight" to setOf("height"),
            "setweight" to setOf("weight"),
            "setbirthyear" to setOf("birthyear"),
            "setsex" to setOf("sex"),
            "setusertype" to setOf("usertype"),
            "setpants" to setOf("pants"),
            "setuniformpantslengthinches" to setOf("pants"),
            "setwaist" to setOf("waist"),
            "setwaistsize" to setOf("waist"),
            "setprofile" to PROFILE_KEYS,
        )

        /** Single-field commands also take a bare value, e.g. `setusertype Coalition Civilian`. */
        val POSITIONAL_KEYS: Map<String, List<String>> =
            COMMAND_KEYS.filterValues { it.size == 1 }.mapValues { it.value.toList() } +
                ("enrollserver" to EnrollServerHandler.POSITIONAL_KEYS) + ("loadprofile" to listOf("name"))
    }
}

/**
 * `#weartaktrackercommandenrollserver name=OPS url=tak.example.org port=8089 username=u password=p`, or
 * `#weartaktrackercommandenrollserver OPS; tak.example.org; 8089; u; p`. Adds the server so the tracker
 * enrolls for a client certificate with those credentials. Credentials are never logged or echoed.
 */
class EnrollServerHandler(
    private val sink: PresetServerSink,
    private val networkLocked: suspend () -> Boolean,
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        val args = command.args
        val extra = args.keys - POSITIONAL_KEYS.toSet()
        if (extra.isNotEmpty()) return ChatCommandResult(false, "enrollserver does not take ${extra.sorted().joinToString(", ")}")
        val name = ChatCommandValidation.serverName(args["name"])
        val address = ChatCommandValidation.serverAddress(args["url"])
        val port = ChatCommandValidation.port(args["port"])
        val username = args["username"]?.trim()?.takeIf { it.isNotEmpty() }
        val password = args["password"]?.takeIf { it.isNotEmpty() }
        if (name == null || address == null || port == null || username == null || password == null) {
            return ChatCommandResult(false, USAGE)
        }
        if (networkLocked()) return ChatCommandResult(false, "Network settings are locked; server not added")
        val added = sink.add(listOf(PresetServer(name, address, port, username, password)))
        return if (added > 0) ChatCommandResult(true, "Added $name ($address:$port); enrolling")
        else ChatCommandResult(false, "$address:$port is already configured; nothing changed")
    }

    companion object {
        val POSITIONAL_KEYS = listOf("name", "url", "port", "username", "password")
        const val USAGE = "enrollserver needs <name>; <host>; <port>; <username>; <password>"
    }
}

data class PresetServer(val name: String, val address: String, val port: Int, val username: String, val password: String)

data class ProfilePreset(
    val profile: Map<String, String>,
    val servers: List<PresetServer>,
    val customAlerts: List<String> = emptyList(),
)

object ProfilePresetParser {
    private val NAME = Regex("[a-z0-9][a-z0-9\\-]{0,31}")

    fun validName(name: String?): String? = name?.trim()?.lowercase()?.takeIf { NAME.matches(it) }

    /** Returns null for malformed JSON or any invalid server or custom alert entry, so a bad preset applies nothing. */
    fun parse(json: String): ProfilePreset? = try {
        val root = JSONObject(json)
        val profile = root.optJSONObject("profile")?.let { o ->
            o.keys().asSequence().associateWith { o.optString(it) }
        }.orEmpty()
        val servers = root.optJSONArray("servers")?.let(::servers) ?: emptyList()
        val alerts = when {
            !root.has(CUSTOM_ALERTS_KEY) -> emptyList()
            else -> root.optJSONArray(CUSTOM_ALERTS_KEY)?.let(::customAlerts) ?: listOf(null)
        }
        if (servers.any { it == null } || alerts.any { it == null }) null
        else ProfilePreset(profile, servers.filterNotNull(), alerts.filterNotNull().distinctBy { it.lowercase() })
    } catch (error: JSONException) {
        null
    }

    const val CUSTOM_ALERTS_KEY = "customAlerts"

    private fun customAlerts(array: JSONArray): List<String?> = (0 until array.length()).map { i ->
        (array.opt(i) as? String)?.let(CustomAlerts::normalize)
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

fun interface CustomAlertSink {
    /** Saves the custom alerts that are not already saved and returns how many were added. */
    suspend fun add(alerts: List<String>): Int
}

/**
 * `#weartaktrackercommandloadprofile name=odin-weartak` applies a preset stored on the device. Credentials
 * come from that file, never from chat, and nothing from it is logged or sent back.
 */
class LoadProfileHandler(
    private val source: PresetSource,
    private val profile: ProfileHandler,
    private val serverSink: PresetServerSink,
    private val networkLocked: suspend () -> Boolean,
    private val customAlertSink: CustomAlertSink = CustomAlertSink { 0 },
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
            val result = profile.execute(ChatCommand("setprofile", preset.profile.mapKeys { it.key.lowercase() }), request)
            if (!result.ok) return ChatCommandResult(false, "Preset $name profile rejected: ${result.message}")
            profileSummary = "profile applied"
        }
        val added = if (preset.servers.isEmpty()) 0 else serverSink.add(preset.servers)
        val alerts = if (preset.customAlerts.isEmpty()) "" else
            ", ${customAlertSink.add(preset.customAlerts)} of ${preset.customAlerts.size} custom alerts added"
        return ChatCommandResult(true, "Loaded $name: $profileSummary, $added of ${preset.servers.size} servers added$alerts")
    }
}
