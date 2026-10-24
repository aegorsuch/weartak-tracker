package com.tak.weartak_tracker.service

import android.content.Context
import java.security.MessageDigest
import org.json.JSONArray

/** Only rosters from authorized addressed GeoChat are learned, never guessed from native room names. */
internal class DittoChatRooms(context: Context) {
    private val preferences = context.getSharedPreferences("ditto_chat_rooms", Context.MODE_PRIVATE)
    private fun key(room: String) = MessageDigest.getInstance("SHA-256").digest(room.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun members(room: String): List<String> {
        val value = preferences.getString(key(room), null) ?: return emptyList()
        val array = JSONArray(value)
        return (0 until array.length()).map { array.getString(it) }
    }

    fun remember(room: String, members: List<String>) {
        val key = key(room)
        check(preferences.contains(key) || preferences.all.size < 256) { "Chat room roster store is full" }
        check(preferences.edit().putString(key, JSONArray(members.distinct()).toString()).commit()) {
            "Cannot persist chat room roster"
        }
    }
}
