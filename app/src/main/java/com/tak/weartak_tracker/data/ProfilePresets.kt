package com.tak.weartak_tracker.data

import android.content.Context
import com.tak.weartak_tracker.command.ProfileHandler
import com.tak.weartak_tracker.command.ProfilePresetParser
import java.io.File

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
