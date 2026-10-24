package com.tak.weartak_tracker.service

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.InflaterInputStream

data class DittoCommunityLicense(
    val appId: String,
    val sharedKey: String,
    val licenseToken: String,
    val expiresAtMillis: Long,
)

object DittoCommunityLicenseHelper {
    private const val MAX_PDL_LENGTH = 128 * 1024
    private const val MAX_JSON_LENGTH = 256 * 1024

    fun parse(encodedPdl: String): DittoCommunityLicense {
        require(encodedPdl.isNotBlank()) { "Ditto Edge Sync identity is empty" }
        require(encodedPdl.length <= MAX_PDL_LENGTH) { "Ditto Edge Sync identity is too large" }

        val compressed = try {
            Base64.getUrlDecoder().decode(encodedPdl.filterNot { it.isWhitespace() })
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Ditto Edge Sync identity is not valid Base64", error)
        }
        val jsonText = try {
            InflaterInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= MAX_JSON_LENGTH) { "Ditto Edge Sync identity payload is too large" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("Could not decompress Ditto Edge Sync identity", error)
        }
        val json = try {
            JSONObject(jsonText)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Ditto Edge Sync identity does not contain valid JSON", error)
        }

        require(json.optInt("v", -1) == 2) { "Unsupported Ditto Edge Sync identity version" }
        require(json.optString("it") == "SK") { "Expected a SharedKey Ditto Edge Sync identity" }

        val license = DittoCommunityLicense(
            appId = json.optString("a"),
            sharedKey = json.optString("s"),
            licenseToken = json.optString("t"),
            expiresAtMillis = json.optLong("e", 0L),
        )
        require(license.appId.isNotBlank()) { "Ditto Edge Sync identity app ID is missing" }
        require(license.sharedKey.isNotBlank()) { "Ditto Edge Sync identity shared key is missing" }
        require(license.licenseToken.isNotBlank()) { "Ditto Edge Sync identity license token is missing" }
        require(license.expiresAtMillis > 0L) { "Ditto Edge Sync identity expiration is missing" }
        return license
    }
}
