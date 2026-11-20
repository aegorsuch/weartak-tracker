package com.tak.weartak_tracker.transport

import com.tak.weartak_tracker.data.SettingsRepository
import com.tak.weartak_tracker.data.TakGovAccessLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class TakGovDeviceCode(val userCode: String)

internal data class TakGovHttpReply(val code: Int, val json: JSONObject?) {
    val successful: Boolean get() = code in 200..299
}

internal fun takGovAccessLevel(groups: JSONArray?): TakGovAccessLevel? {
    if (groups == null) return null
    val names = mutableSetOf<String>()
    for (index in 0 until groups.length()) {
        val name = groups.opt(index) as? String ?: return null
        names += name
    }
    return when {
        "Website Military" in names -> TakGovAccessLevel.MIL
        "Website Public" in names -> TakGovAccessLevel.CIV
        else -> null
    }
}

internal class TakGovDeviceClient(
    private val repo: SettingsRepository,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun link(onDeviceCode: (TakGovDeviceCode) -> Unit) {
        val deviceReply = postForm(
            DEVICE_AUTHORIZATION_ENDPOINT,
            mapOf("client_id" to CLIENT_ID, "scope" to SCOPE),
        )
        if (!deviceReply.successful) {
            throw IOException("TAK.gov device authorization failed (HTTP ${deviceReply.code})")
        }

        val device = deviceReply.json
            ?: throw IOException("TAK.gov device authorization returned an invalid response")
        val deviceCode = device.optString("device_code")
        val userCode = device.optString("user_code")
        if (deviceCode.isBlank() || userCode.isBlank()) {
            throw IOException("TAK.gov device authorization returned an invalid response")
        }
        onDeviceCode(TakGovDeviceCode(userCode))

        val expiresIn = device.optLong("expires_in", 600).coerceAtLeast(1)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(expiresIn)
        var intervalSeconds = device.optLong("interval", 5).coerceAtLeast(1)
        while (System.nanoTime() < deadline) {
            delay(TimeUnit.SECONDS.toMillis(intervalSeconds))
            val tokenReply = postForm(
                TOKEN_ENDPOINT,
                mapOf(
                    "client_id" to CLIENT_ID,
                    "device_code" to deviceCode,
                    "grant_type" to DEVICE_CODE_GRANT,
                ),
            )
            val body = tokenReply.json
            val refreshToken = body?.optString("refresh_token").orEmpty()
            if (tokenReply.successful && refreshToken.isNotBlank()) {
                repo.saveTakGovRefreshToken(refreshToken)
                val level = lookupAccessLevel(body?.optString("access_token").orEmpty())
                repo.setTakGovAccessLevel(level)
                lastRoleLookupNanos = System.nanoTime()
                return
            }
            when (body?.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalSeconds = (intervalSeconds + 5).coerceAtMost(3_600)
                "access_denied" -> throw IOException("TAK.gov authorization was denied")
                "expired_token" -> throw IOException("TAK.gov authorization code expired")
                else -> throw IOException("TAK.gov authorization failed (HTTP ${tokenReply.code})")
            }
        }
        throw IOException("TAK.gov authorization code expired")
    }

    suspend fun refreshAccessLevelIfDue() {
        val now = System.nanoTime()
        if (lastRoleLookupNanos != 0L && now - lastRoleLookupNanos < ROLE_LOOKUP_INTERVAL_NANOS) return
        lastRoleLookupNanos = now

        val oldRefreshToken = repo.takGovRefreshToken() ?: return
        val reply = try {
            postForm(
                TOKEN_ENDPOINT,
                mapOf(
                    "client_id" to CLIENT_ID,
                    "grant_type" to REFRESH_TOKEN_GRANT,
                    "refresh_token" to oldRefreshToken,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            repo.setTakGovAccessLevel(null)
            return
        }
        val body = reply.json
        if (!reply.successful || body == null) {
            repo.setTakGovAccessLevel(null)
            return
        }

        body.optString("refresh_token").takeIf(String::isNotBlank)?.let { rotated ->
            repo.saveTakGovRefreshToken(rotated, resetAccessLevel = false)
        }
        repo.setTakGovAccessLevel(lookupAccessLevel(body.optString("access_token")))
    }

    private suspend fun lookupAccessLevel(accessToken: String): TakGovAccessLevel? {
        if (accessToken.isBlank()) return null
        return try {
            val reply = getUserInfo(accessToken)
            if (reply.successful) takGovAccessLevel(reply.json?.optJSONArray("groups")) else null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getUserInfo(accessToken: String): TakGovHttpReply {
        val request = Request.Builder()
            .url(USER_INFO_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return execute(request)
    }

    private suspend fun postForm(url: String, fields: Map<String, String>): TakGovHttpReply {
        val form = FormBody.Builder().apply {
            fields.forEach { (name, value) -> add(name, value) }
        }.build()
        return execute(Request.Builder().url(url).post(form).build())
    }

    private suspend fun execute(request: Request): TakGovHttpReply = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            TakGovHttpReply(response.code, runCatching { JSONObject(body) }.getOrNull())
        }
    }

    private companion object {
        const val CLIENT_ID = "tak-gov-eud"
        const val SCOPE = "openid offline_access email profile"
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val REFRESH_TOKEN_GRANT = "refresh_token"
        const val DEVICE_AUTHORIZATION_ENDPOINT =
            "https://auth.tak.gov/auth/realms/TPC/protocol/openid-connect/auth/device"
        const val TOKEN_ENDPOINT =
            "https://auth.tak.gov/auth/realms/TPC/protocol/openid-connect/token"
        const val USER_INFO_ENDPOINT =
            "https://auth.tak.gov/auth/realms/TPC/protocol/openid-connect/userinfo"
        const val ROLE_LOOKUP_INTERVAL_NANOS = 6L * 60L * 60L * 1_000_000_000L

        @Volatile
        var lastRoleLookupNanos = 0L
    }
}
