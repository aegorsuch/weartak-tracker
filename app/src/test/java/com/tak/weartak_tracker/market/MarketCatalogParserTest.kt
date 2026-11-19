package com.tak.weartak_tracker.market

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class MarketCatalogParserTest {
    private val hostPackage = "com.tak.weartak_tracker"

    @Test
    fun acceptsSignedCommunityReleaseAndNormalizesHashes() {
        val keys = newKeyPair()
        val catalog = parse(signedPluginCatalog(pluginRecord(), keys), keys)

        assertEquals(1, catalog.plugins.size)
        assertEquals(MarketPublisherType.COMMUNITY, catalog.plugins.single().publisherType)
        assertTrue(catalog.plugins.single().apkSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun rejectsCatalogSignedByUnexpectedKey() {
        val keys = newKeyPair()
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(pluginRecord(), keys), newKeyPair())
        }
    }

    @Test
    fun onlyOfficialUpdatesCanTargetTrackerHost() {
        val keys = newKeyPair()
        val record = pluginRecord().apply {
            put("packageName", hostPackage)
            put("hostUpdate", true)
        }
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(record, keys), keys)
        }

        val official = pluginRecord().apply {
            put("publisherType", "official")
            put("packageName", hostPackage)
            put("hostUpdate", true)
        }
        assertEquals(hostPackage, parse(signedPluginCatalog(official, keys), keys).plugins.single().packageName)
    }

    @Test
    fun rejectsCatalogContainingUpdatesForAnotherHost() {
        val keys = newKeyPair()
        val records = JSONArray()
            .put(pluginRecord())
            .put(pluginRecord().apply {
                put("id", "wearos-host")
                put("publisherType", "official")
                put("packageName", "com.aitech.weartak_civ")
                put("hostUpdate", true)
            })
        assertThrows(MarketCatalogException::class.java) {
            parse(signedEnvelope(JSONObject().put("schemaVersion", 1).put("plugins", records), keys), keys)
        }
    }

    @Test
    fun rejectsHttpArtifactUrlsAndDuplicatePackages() {
        val keys = newKeyPair()
        val insecure = pluginRecord().put("apkUrl", "http://plugins.example/release.apk")
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(insecure, keys), keys)
        }

        val duplicate = JSONObject()
            .put("schemaVersion", 1)
            .put("plugins", JSONArray().put(pluginRecord()).put(pluginRecord().put("id", "other")))
        assertThrows(MarketCatalogException::class.java) {
            parse(signedEnvelope(duplicate, keys), keys)
        }
    }

    @Test
    fun buildConfigurationIsDisabledWithoutBothValues() {
        assertFalse(MarketConfiguration("", "").isConfigured)
        assertFalse(MarketConfiguration("https://catalog.example/index.json", "").isConfigured)
        assertTrue(MarketConfiguration("https://catalog.example/index.json", "public-key").isConfigured)
    }

    private fun parse(envelope: ByteArray, keys: KeyPair) =
        MarketCatalogParser.parse(
            envelopeBytes = envelope,
            publicKeyBase64 = Base64.getEncoder().encodeToString(keys.public.encoded),
            hostPackage = hostPackage,
        )

    private fun signedPluginCatalog(payload: JSONObject, keys: KeyPair): ByteArray =
        signedEnvelope(JSONObject().put("schemaVersion", 1).put("plugins", JSONArray().put(payload)), keys)

    private fun signedEnvelope(payload: JSONObject, keys: KeyPair): ByteArray {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keys.private)
        signer.update(bytes)
        return JSONObject()
            .put("payload", Base64.getEncoder().encodeToString(bytes))
            .put("signature", Base64.getEncoder().encodeToString(signer.sign()))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun pluginRecord() = JSONObject()
        .put("id", "community-weather")
        .put("name", "Weather")
        .put("description", "Field weather plugin")
        .put("publisher", "Community Maintainers")
        .put("publisherType", "community")
        .put("packageName", "org.example.weartak.weather")
        .put("versionName", "1.2.0")
        .put("versionCode", 12)
        .put("minHostVersionCode", 5840000)
        .put("maxHostVersionCode", JSONObject.NULL)
        .put("apkUrl", "https://releases.example/weather.apk")
        .put("apkSha256", "A".repeat(64))
        .put("signerSha256", "B".repeat(64))
        .put("sourceUrl", "https://github.com/example/weather")
        .put("hostUpdate", false)
}
