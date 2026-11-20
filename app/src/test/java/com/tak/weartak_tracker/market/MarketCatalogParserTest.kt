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
    fun keepsLegacyTrackerCatalogEntriesVisibleDuringRollout() {
        val keys = newKeyPair()
        val catalog = parse(signedPluginCatalog(legacyPluginRecord(), keys, schemaVersion = 1), keys)

        assertEquals(1, catalog.plugins.size)
        assertEquals(MarketTargetIds.WEARTAK_TRACKER, catalog.plugins.single().targets.single().productId)
        assertTrue(catalog.plugins.single().targets.single().legacy)
    }

    @Test
    fun filtersSchemaTwoEntriesByExactProductAndEdition() {
        val keys = newKeyPair()
        val trackerMil = pluginRecord().put(
            "targets",
            JSONArray().put(target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.MIL, 5_860_000, null)),
        )
        val trackerCiv = pluginRecord().apply {
            put("id", "tracker-civ")
            put("packageName", "org.example.tracker.civ")
            put("targets", JSONArray().put(target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.CIV, 1, null)))
        }
        val wearOsMil = pluginRecord().apply {
            put("id", "wearos-mil")
            put("packageName", "org.example.wearos.mil")
            put("targets", JSONArray().put(target(MarketTargetIds.WEARTAK_WEAROS, MarketTargetIds.MIL, 1, null)))
        }
        val payload = JSONObject()
            .put("schemaVersion", 2)
            .put("plugins", JSONArray().put(trackerMil).put(trackerCiv).put(wearOsMil))

        val catalog = parse(
            signedEnvelope(payload, keys),
            keys,
            hostEditionIds = setOf(MarketTargetIds.MIL),
        )

        assertEquals(listOf("community-weather"), catalog.plugins.map { it.id })
    }

    @Test
    fun displaysTrackerTargetsForBothSupportedEditions() {
        val keys = newKeyPair()
        val trackerCiv = pluginRecord().apply {
            put("id", "tracker-civ")
            put("packageName", "org.example.tracker.civ")
            put("targets", JSONArray().put(target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.CIV, 1, null)))
        }
        val trackerMil = pluginRecord().apply {
            put("id", "tracker-mil")
            put("packageName", "org.example.tracker.mil")
            put("targets", JSONArray().put(target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.MIL, 1, null)))
        }
        val payload = JSONObject()
            .put("schemaVersion", 2)
            .put("plugins", JSONArray().put(trackerCiv).put(trackerMil))

        val catalog = parse(signedEnvelope(payload, keys), keys)

        assertEquals(listOf("tracker-civ", "tracker-mil"), catalog.plugins.map { it.id })
    }

    @Test
    fun acceptsOtherProductHostUpdatesInSharedCatalogButDoesNotDisplayThem() {
        val keys = newKeyPair()
        val otherHostUpdate = pluginRecord().apply {
            put("id", "wearos-host")
            put("name", "WearTAK")
            put("publisherType", "official")
            put("packageName", "com.aitech.weartak_civ")
            put("hostUpdate", true)
            put(
                "targets",
                JSONArray().put(target(MarketTargetIds.WEARTAK_WEAROS, MarketTargetIds.CIV, 1, null)),
            )
        }
        val payload = JSONObject()
            .put("schemaVersion", 2)
            .put("plugins", JSONArray().put(pluginRecord()).put(otherHostUpdate))

        val catalog = parse(signedEnvelope(payload, keys), keys)

        assertEquals(listOf("community-weather"), catalog.plugins.map { it.id })
    }

    @Test
    fun rejectsInvalidTargetMetadataAndUnsupportedBuildEditions() {
        val keys = newKeyPair()
        val duplicateProduct = pluginRecord().put(
            "targets",
            JSONArray()
                .put(target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.MIL, 1, null))
                .put(target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.CIV, 1, null)),
        )
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(duplicateProduct, keys), keys)
        }

        val unknownEdition = pluginRecord().put(
            "targets",
            JSONArray().put(target(MarketTargetIds.WEARTAK_TRACKER, "other", 1, null)),
        )
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(unknownEdition, keys), keys)
        }

        val missingTargets = pluginRecord().also { it.remove("targets") }
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(missingTargets, keys), keys)
        }

        val invalidRange = pluginRecord().put(
            "targets",
            JSONArray().put(target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.MIL, 200, 100)),
        )
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(invalidRange, keys), keys)
        }

        val error = assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(pluginRecord(), keys), keys, hostEditionIds = emptySet())
        }
        assertTrue(error.unsupportedTarget)
    }

    @Test
    fun requiresExactSchemaTwoTargetFieldsAndRejectsLegacyRangeMixing() {
        val keys = newKeyPair()
        val missingField = pluginRecord().put(
            "targets",
            JSONArray().put(
                target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.MIL, 1, null)
                    .also { it.remove("maxHostVersionCode") },
            ),
        )
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(missingField, keys), keys)
        }

        val unknownField = pluginRecord().put(
            "targets",
            JSONArray().put(
                target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.MIL, 1, null)
                    .put("unexpected", true),
            ),
        )
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(unknownField, keys), keys)
        }

        val mixedLegacyRange = pluginRecord()
            .put("minHostVersionCode", 1)
            .put("maxHostVersionCode", JSONObject.NULL)
        assertThrows(MarketCatalogException::class.java) {
            parse(signedPluginCatalog(mixedLegacyRange, keys), keys)
        }
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
            .put(legacyPluginRecord())
            .put(legacyPluginRecord().apply {
                put("id", "wearos-host")
                put("publisherType", "official")
                put("packageName", "com.aitech.weartak_civ")
                put("hostUpdate", true)
            })
        assertThrows(MarketCatalogException::class.java) {
            parse(
                signedEnvelope(JSONObject().put("schemaVersion", 1).put("plugins", records), keys),
                keys,
            )
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
        assertEquals(
            setOf(MarketTargetIds.CIV, MarketTargetIds.MIL),
            MarketConfiguration.fromBuildConfig().editionIds,
        )
    }

    private fun parse(
        envelope: ByteArray,
        keys: KeyPair,
        hostProductId: String = MarketTargetIds.WEARTAK_TRACKER,
        hostEditionIds: Set<String> = MarketTargetIds.editions,
    ) =
        MarketCatalogParser.parse(
            envelopeBytes = envelope,
            publicKeyBase64 = Base64.getEncoder().encodeToString(keys.public.encoded),
            hostPackage = hostPackage,
            hostProductId = hostProductId,
            hostEditionIds = hostEditionIds,
        )

    private fun signedPluginCatalog(
        payload: JSONObject,
        keys: KeyPair,
        schemaVersion: Int = 2,
    ): ByteArray =
        signedEnvelope(JSONObject().put("schemaVersion", schemaVersion).put("plugins", JSONArray().put(payload)), keys)

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
        .put(
            "targets",
            JSONArray().put(target(MarketTargetIds.WEARTAK_TRACKER, MarketTargetIds.MIL, 5_860_000, null)),
        )
        .put("apkUrl", "https://releases.example/weather.apk")
        .put("apkSha256", "A".repeat(64))
        .put("signerSha256", "B".repeat(64))
        .put("sourceUrl", "https://github.com/example/weather")
        .put("hostUpdate", false)

    private fun legacyPluginRecord() = pluginRecord()
        .put("minHostVersionCode", 5_860_000)
        .put("maxHostVersionCode", JSONObject.NULL)
        .also { it.remove("targets") }

    private fun target(
        productId: String,
        editionId: String,
        minHostVersionCode: Long,
        maxHostVersionCode: Long?,
    ) = JSONObject()
        .put("productId", productId)
        .put("editionIds", JSONArray().put(editionId))
        .put("minHostVersionCode", minHostVersionCode)
        .put("maxHostVersionCode", maxHostVersionCode ?: JSONObject.NULL)
}
