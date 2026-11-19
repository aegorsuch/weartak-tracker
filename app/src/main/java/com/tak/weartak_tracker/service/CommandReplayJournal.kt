package com.tak.weartak_tracker.service

import android.content.Context
import java.security.MessageDigest

/** Only message-key digests and receipt times persist; no command bodies or credentials. */
internal class CommandReplayJournal(context: Context) {
    private val preferences = context.getSharedPreferences("command_replay", Context.MODE_PRIVATE)

    @Synchronized
    fun claim(key: String, now: Long): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val retained = preferences.all.filterValues { it is Long && it > now - RETAIN_MS }
        if (digest in retained) return false
        check(retained.size < 4096) { "Command replay journal is full" }
        val editor = preferences.edit()
        (preferences.all.keys - retained.keys).forEach(editor::remove)
        check(editor.putLong(digest, now).commit()) { "Cannot persist command replay journal" }
        return true
    }

    private companion object { const val RETAIN_MS = 15 * 60_000L }
}
