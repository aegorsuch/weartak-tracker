package com.tak.weartak_tracker.command

import java.util.Locale

enum class ChatCommandDecision { ALLOWED, SENDER_NOT_ALLOWED, STALE, DUPLICATE }

/**
 * Decides whether a parsed chat command may run. Senders are matched by callsign (case-insensitive),
 * so the allow-list is a convenience filter, not authentication: anyone who can pick an allowed
 * callsign on the same server can send commands, which is why server enrollment still prompts.
 */
class ChatCommandAuthorizer(
    private val maxAgeMs: Long = 5 * 60_000L,
    private val maxRemembered: Int = 128,
    private val claim: ((String, Long) -> Boolean)? = null,
) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun authorize(
        senderCallsign: String,
        allowedCallsigns: Collection<String>,
        messageKey: String,
        sentAtMs: Long,
        nowMs: Long
    ): ChatCommandDecision {
        val sender = normalize(senderCallsign)
        if (sender.isEmpty() || allowedCallsigns.none { normalize(it) == sender }) {
            return ChatCommandDecision.SENDER_NOT_ALLOWED
        }
        if (sentAtMs <= 0 || nowMs - sentAtMs > maxAgeMs || sentAtMs - nowMs > maxAgeMs) {
            return ChatCommandDecision.STALE
        }
        val key = "$sender|$messageKey"
        if (claim != null) {
            return if (claim.invoke(key, nowMs)) ChatCommandDecision.ALLOWED else ChatCommandDecision.DUPLICATE
        }
        if (!seen.add(key)) return ChatCommandDecision.DUPLICATE
        while (seen.size > maxRemembered) seen.remove(seen.first())
        return ChatCommandDecision.ALLOWED
    }

    companion object {
        fun normalize(callsign: String) = callsign.trim().lowercase()

        fun parseAllowList(text: String): List<String> =
            sortedAllowList(text.split(Regex("[,\\r\\n]+")))

        fun sortedAllowList(callsigns: Collection<String>): List<String> =
            callsigns.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy(::normalize)
                .map { it.uppercase(Locale.ROOT) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

        fun validateNewCallsign(value: String, allowedCallsigns: Collection<String>): String? {
            if (ChatCommandValidation.callsign(value) == null) {
                return "Enter a valid callsign (1-32 letters, numbers, spaces, dots, underscores, or hyphens)"
            }
            if (allowedCallsigns.any { normalize(it) == normalize(value) }) {
                return "This callsign is already allowed"
            }
            return null
        }

        fun addAllowedCallsign(allowedCallsigns: Collection<String>, value: String): List<String>? {
            if (validateNewCallsign(value, allowedCallsigns) != null) return null
            return sortedAllowList(allowedCallsigns + value)
        }

        fun removeAllowedCallsign(allowedCallsigns: Collection<String>, value: String): List<String> =
            sortedAllowList(allowedCallsigns.filterNot { normalize(it) == normalize(value) })
    }
}