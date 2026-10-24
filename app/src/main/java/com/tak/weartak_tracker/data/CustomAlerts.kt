package com.tak.weartak_tracker.data

import org.json.JSONArray

/** Built-in manual alerts, alphabetical. */
val PRESET_MANUAL_ALERTS = listOf(
    "911 Alert",
    "Gate Runner",
    "Geofence Breached",
    "Gunshot",
    "Gunshot Injury",
    "In Contact",
    "Injury",
    "Ring The Bell",
    "UAS",
    "Vehicle",
).sorted()

/** Saved custom manual alerts: single-line, de-duplicated (case-insensitively, including presets) and capped. */
object CustomAlerts {
    const val MAX_LENGTH = 40
    const val MAX_COUNT = 20

    /** The single-line text to save, or null when blank, too long or containing control characters. */
    fun normalize(text: String?): String? {
        val raw = text ?: return null
        if (raw.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
        val value = raw.trim().replace(Regex("\\s+"), " ")
        return value.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    }

    fun isDuplicate(saved: List<String>, text: String): Boolean =
        (PRESET_MANUAL_ALERTS + saved).any { it.equals(text, ignoreCase = true) }

    /** [saved] plus [text], or null when [text] is invalid, already present, or the list is full. */
    fun add(saved: List<String>, text: String): List<String>? {
        val value = normalize(text) ?: return null
        if (isDuplicate(saved, value) || saved.size >= MAX_COUNT) return null
        return saved + value
    }

    fun remove(saved: List<String>, text: String): List<String> = saved.filterNot { it == text }

    /** Why [text] can't be saved, or null when [add] will accept it. */
    fun validationError(saved: List<String>, text: String): String? {
        val value = normalize(text)
        return when {
            text.isBlank() -> "Please enter an alert"
            value == null && text.trim().length > MAX_LENGTH -> "Alert must be $MAX_LENGTH characters or fewer"
            value == null -> "Alert can't contain control characters"
            isDuplicate(saved, value) -> "Alert already exists"
            saved.size >= MAX_COUNT -> "Up to $MAX_COUNT custom alerts can be saved"
            else -> null
        }
    }

    /** Adds every new valid entry of [incoming] in order; returns the merged list and how many were added. */
    fun merge(saved: List<String>, incoming: List<String>): Pair<List<String>, Int> {
        var result = saved
        incoming.forEach { text -> add(result, text)?.let { result = it } }
        return result to (result.size - saved.size)
    }

    fun encode(saved: List<String>): String = JSONArray(saved).toString()

    fun decode(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return merge(emptyList(), (0 until array.length()).map { array.optString(it) }).first
    }
}
