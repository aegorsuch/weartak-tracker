package com.tak.weartak_tracker.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSecurityPolicyTest {
    private val hostPackage = "com.tak.weartak_tracker"
    private val signer = "a".repeat(64)

    @Test
    fun comparesInstalledVersionAndHostCompatibility() {
        assertEquals(
            MarketInstallState.INSTALL,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, null, null, false),
        )
        assertEquals(
            MarketInstallState.UPDATE,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, 4, setOf(signer), false),
        )
        assertEquals(
            MarketInstallState.UP_TO_DATE,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, 5, setOf(signer), false),
        )
        assertEquals(
            MarketInstallState.NEWER_VERSION_INSTALLED,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, 6, setOf(signer), false),
        )
        assertEquals(
            MarketInstallState.INCOMPATIBLE,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 201, null, null, false),
        )
    }

    @Test
    fun blocksPlayManagedHostUpdatesAndSignerChanges() {
        val hostUpdate = plugin(packageName = hostPackage, hostUpdate = true)
        assertEquals(
            MarketInstallState.PLAY_MANAGED_HOST,
            MarketSecurityPolicy.installState(hostUpdate, hostPackage, 100, 4, setOf(signer), true),
        )
        assertEquals(
            MarketInstallState.SIGNER_MISMATCH,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, 4, setOf("c".repeat(64)), false),
        )
    }

    @Test
    fun reviewedUpdatesStayHiddenUntilProcessRestart() {
        val first = plugin()
        val second = plugin(id = "another", packageName = "org.example.another")
        val updates = listOf(first, second)
        val review = MarketUpdateReview()

        assertTrue(review.shouldShowWarning(updates))
        review.markReviewed()
        assertFalse(review.shouldShowWarning(updates.reversed()))
        assertFalse(review.shouldShowWarning(listOf(first.copy(versionCode = 6), second)))
        assertFalse(review.shouldShowWarning(emptyList()))
        assertFalse(review.shouldShowWarning(updates))
        assertTrue(MarketUpdateReview().shouldShowWarning(updates))
    }

    @Test
    fun onlyInstalledOlderReleasesQualifyAsUpdates() {
        val host = plugin(packageName = hostPackage, hostUpdate = true)
        val installedPlugin = plugin()
        val newPlugin = plugin(id = "new", packageName = "org.example.newplugin")
        fun updatesFor(releases: List<MarketPlugin>) = releases.filter { release ->
            val installed = if (release == newPlugin) null else 4L
            MarketSecurityPolicy.installState(
                release, hostPackage, 100, installed,
                if (installed == null) null else setOf(signer), false,
            ) == MarketInstallState.UPDATE
        }
        val updates = updatesFor(listOf(host, installedPlugin, newPlugin))

        assertEquals(listOf(host, installedPlugin), updates)
        assertTrue(MarketUpdateReview().shouldShowWarning(updates))
        assertFalse(MarketUpdateReview().shouldShowWarning(updatesFor(listOf(newPlugin))))
    }

    @Test
    fun archiveMustMatchCatalogPackageVersionAndSigner() {
        val release = plugin()
        assertTrue(MarketSecurityPolicy.archiveMatches(release, release.packageName, 5, setOf(signer)))
        assertFalse(MarketSecurityPolicy.archiveMatches(release, "org.example.other", 5, setOf(signer)))
        assertFalse(MarketSecurityPolicy.archiveMatches(release, release.packageName, 6, setOf(signer)))
        assertFalse(MarketSecurityPolicy.archiveMatches(release, release.packageName, 5, setOf("c".repeat(64))))
    }

    @Test
    fun computesStandardSha256Hex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".toByteArray()),
        )
    }

    private fun plugin(
        id: String = "weather",
        packageName: String = "org.example.weather",
        hostUpdate: Boolean = false,
    ) = MarketPlugin(
        id = id,
        name = "Weather",
        description = "Field weather",
        publisher = "Community",
        publisherType = if (hostUpdate) MarketPublisherType.OFFICIAL else MarketPublisherType.COMMUNITY,
        packageName = packageName,
        versionName = "1.0",
        versionCode = 5,
        minHostVersionCode = 90,
        maxHostVersionCode = 200,
        apkUrl = "https://releases.example/weather.apk",
        apkSha256 = "b".repeat(64),
        signerSha256 = signer,
        sourceUrl = null,
        hostUpdate = hostUpdate,
    )
}
