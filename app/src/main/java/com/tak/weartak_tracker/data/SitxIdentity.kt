package com.tak.weartak_tracker.data

import org.json.JSONObject
import java.util.Base64
import java.util.Locale

data class SitxAccount(val label: String, val isNpe: Boolean)

fun sitxAccountValue(authorized: Boolean, account: SitxAccount?, notAuthorized: String): String =
    if (!authorized) notAuthorized else account?.label.orEmpty()

fun formatSitxPairingCode(code: String): String =
    if (code.matches(Regex("[A-Za-z0-9]{8}"))) {
        code.uppercase(Locale.ROOT).let { "${it.substring(0, 4)}-${it.substring(4)}" }
    } else {
        code
    }

fun decodeSitxAccount(accessToken: String?): SitxAccount? {
    if (accessToken.isNullOrBlank()) return null
    val claims = runCatching {
        val parts = accessToken.split('.')
        require(parts.size == 3)
        val payload = Base64.getUrlDecoder().decode(parts[1])
        JSONObject(String(payload, Charsets.UTF_8))
    }.getOrNull() ?: return null

    val email = claims.claim("user_email")
    val callsign = claims.claim("callsign")
    val accessType = claims.claim("access_type")
    val isNpe = (email != null && '@' !in email) || (!accessType.isNullOrBlank() && accessType != "user")
    val label = if (isNpe) {
        email?.takeIf { '@' !in it } ?: callsign ?: "NPE"
    } else {
        email?.takeIf { '@' in it } ?: callsign ?: return null
    }
    return SitxAccount(label = label, isNpe = isNpe)
}

fun isValidSitxReauthPin(pin: String): Boolean =
    pin.length == 6 && pin.all { it in '0'..'9' }

/** Optional account metadata remains compatible with settings snapshots written before these fields existed. */
data class SitxAccountSnapshot(val account: String?, val isNpe: Boolean?)

fun decodeSitxAccountSnapshot(json: String): SitxAccountSnapshot {
    val value = JSONObject(json)
    return SitxAccountSnapshot(
        account = value.optString("sitxAccount").takeIf { value.has("sitxAccount") && it.isNotBlank() },
        isNpe = if (value.has("sitxIsNpe")) value.optBoolean("sitxIsNpe") else null,
    )
}

private fun JSONObject.claim(name: String): String? =
    (opt(name) as? String)?.trim()?.takeIf(String::isNotEmpty)
