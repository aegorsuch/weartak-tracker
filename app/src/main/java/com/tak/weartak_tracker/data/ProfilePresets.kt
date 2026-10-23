package com.tak.weartak_tracker.data

import android.content.Context
import com.tak.weartak_tracker.command.ChatCommandValidation
import com.tak.weartak_tracker.command.ProfileHandler
import com.tak.weartak_tracker.command.ProfilePresetParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.Year
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Read-only view of a saved preset. It never carries server usernames or passwords. */
data class PresetSummary(
    val name: String,
    val valid: Boolean,
    val profile: List<Pair<String, String>>,
    val servers: List<String>,
)

/**
 * `loadprofile` presets: `Android/data/<package>/files/presets/<name>.json`, pushed to the watch with adb.
 */
object ProfilePresets {
    const val MAX_BYTES = 64 * 1024L

    private fun dir(context: Context): File? = context.getExternalFilesDir("presets")

    fun read(context: Context, name: String): String? {
        if (ProfilePresetParser.validName(name) != name) return null
        val file = File(dir(context) ?: return null, "$name.json")
        return if (!file.isFile || file.length() > MAX_BYTES) null else file.readText()
    }

    /** Valid preset names on this device, sorted. */
    fun names(context: Context): List<String> =
        dir(context)?.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".json").takeIf { ProfilePresetParser.validName(it) == it } }
            .sorted()

    fun summary(context: Context, name: String): PresetSummary = summarize(name, read(context, name))

    /** Deletes a preset file. Loading never deletes; presets are kept as backups. */
    fun delete(context: Context, name: String): Boolean = dir(context)?.let { delete(it, name) } ?: false

    internal fun delete(dir: File, name: String): Boolean {
        if (ProfilePresetParser.validName(name) != name) return false
        val file = File(dir, "$name.json")
        return file.isFile && file.delete()
    }

    /** Saves [config] as a new preset named `backup-<date>-<time>` (never overwriting) and returns its name. */
    fun saveBackup(context: Context, config: TrackerConfig, now: LocalDateTime = LocalDateTime.now()): String? =
        dir(context)?.let { saveBackup(it, config, now) }

    internal fun saveBackup(dir: File, config: TrackerConfig, now: LocalDateTime): String? {
        dir.mkdirs()
        val base = "backup-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US))
        val name = (sequenceOf(base) + (2..99).asSequence().map { "$base-$it" })
            .firstOrNull { !File(dir, "$it.json").exists() } ?: return null
        val tmp = File(dir, ".$name.tmp")
        return try {
            tmp.writeText(encode(config))
            if (tmp.renameTo(File(dir, "$name.json"))) name else null
        } finally {
            tmp.delete()
        }
    }

    /**
     * Current settings in loadprofile preset form. Unset fields are left out, and servers that a preset
     * can't hold (no enrollment username/password, or a name/host loadprofile would reject) are skipped,
     * so the result always loads. Server passwords are included because loadprofile enrolls with them.
     */
    fun encode(config: TrackerConfig): String {
        val m = config.medicalProfile
        val profile = JSONObject()
        ChatCommandValidation.callsign(config.callsign)?.let { profile.put("callsign", it) }
        if (config.team in TEAMS) profile.put("team", config.team)
        if (ROLE_CATEGORIES.any { config.role in it.roles }) profile.put("role", config.role)
        if (m.heightFeet in 1..8 && m.heightInches in 0..11) profile.put("height", "${m.heightFeet}'${m.heightInches}\"")
        if (m.weightLbs in 1..1000) profile.put("weight", m.weightLbs.toString())
        m.birthYear?.takeIf { it in 1900..Year.now().value }?.let { profile.put("birthyear", it.toString()) }
        if (m.sex in MEDICAL_SEX_OPTIONS) profile.put("sex", m.sex)
        if (m.userType in MEDICAL_USER_TYPE_OPTIONS) profile.put("usertype", m.userType)
        if (m.pantsLengthInches in ProfileHandler.PANTS_RANGE) profile.put("pants", m.pantsLengthInches.toString())
        if (m.waistInches in ProfileHandler.WAIST_RANGE) profile.put("waist", m.waistInches.toString())
        val servers = JSONArray()
        config.servers.forEach { s ->
            val name = ChatCommandValidation.serverName(s.name) ?: ChatCommandValidation.serverName(s.address.take(32))
            val address = ChatCommandValidation.serverAddress(s.address)
            if (name == null || address == null || s.username.isBlank() || s.password.isEmpty()) return@forEach
            servers.put(
                JSONObject().put("name", name).put("url", address).put("port", s.port)
                    .put("username", s.username).put("password", s.password),
            )
        }
        return JSONObject().put("profile", profile).put("servers", servers).toString(2)
    }

    fun label(key: String): String = LABELS[key] ?: key

    private val LABELS = mapOf(
        "callsign" to "Callsign", "team" to "Team", "role" to "Role", "height" to "Height", "weight" to "Weight (lbs)",
        "birthyear" to "Birth Year", "sex" to "Sex", "usertype" to "User Type",
        "pants" to "Uniform Pants Length (in)", "waist" to "Uniform Waist Size (in)",
    )

    fun summarize(name: String, json: String?): PresetSummary {
        val preset = json?.let(ProfilePresetParser::parse)
            ?: return PresetSummary(name, false, emptyList(), emptyList())
        return PresetSummary(
            name = name,
            valid = true,
            profile = preset.profile.entries.map { it.key.lowercase() to it.value }
                .sortedBy { ProfileHandler.PROFILE_KEYS.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i } },
            servers = preset.servers.map { "${it.name} (${it.address}:${it.port})" },
        )
    }
}
