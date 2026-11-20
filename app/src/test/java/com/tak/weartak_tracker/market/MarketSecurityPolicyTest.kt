package com.tak.weartak_tracker.market

import com.tak.weartak_tracker.ui.marketPluginsInDisplayOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSecurityPolicyTest {
    private val hostPackage = "com.tak.weartak_tracker"
    private val signer = "a".repeat(64)

    @Test
    fun pluginDisplayOrderIgnoresCaseAndUsesVisibleHostName() {
        val host = plugin(packageName = hostPackage, hostUpdate = true).copy(name = "AAA host metadata")
        val openVpn = plugin(id = "vpn", packageName = "org.example.vpn").copy(name = "OpenVPN")
        val alpha = plugin(id = "alpha", packageName = "org.example.alpha").copy(name = "alpha")
        val zulu = plugin(id = "zulu", packageName = "org.example.zulu").copy(name = "Zulu")

        assertEquals(
            listOf(alpha, openVpn, host, zulu),
            marketPluginsInDisplayOrder(listOf(zulu, host, openVpn, alpha), "WearTAK-Tracker"),
        )
    }

    @Test
    fun comparesInstalledVersionAndHostCompatibility() {
        assertEquals(
            MarketInstallState.INSTALL,
            state(plugin(), 100, null, null, false),
        )
        assertEquals(
            MarketInstallState.UPDATE,
            state(plugin(), 100, 4, setOf(signer), false),
        )
        assertEquals(
            MarketInstallState.UP_TO_DATE,
            state(plugin(), 100, 5, setOf(signer), false),
        )
        assertEquals(
            MarketInstallState.NEWER_VERSION_INSTALLED,
            state(plugin(), 100, 6, setOf(signer), false),
        )
        assertEquals(
            MarketInstallState.INCOMPATIBLE,
            state(plugin(), 201, null, null, false),
        )
    }

    @Test
    fun blocksPlayManagedHostUpdatesAndSignerChanges() {
        val hostUpdate = plugin(packageName = hostPackage, hostUpdate = true)
        assertEquals(
            MarketInstallState.PLAY_MANAGED_HOST,
            state(hostUpdate, 100, 4, setOf(signer), true),
        )
        assertEquals(
            MarketInstallState.SIGNER_MISMATCH,
            state(plugin(), 100, 4, setOf("c".repeat(64)), false),
        )
    }

    @Test
    fun compatibilityUsesOnlyMatchingProductAndEditionRange() {
        val multiTarget = plugin().copy(
            targets = listOf(
                MarketTarget(
                    MarketTargetIds.WEARTAK_TRACKER,
                    setOf(MarketTargetIds.MIL),
                    MarketHostVersionRange(90, 200),
                ),
                MarketTarget(
                    MarketTargetIds.WEARTAK_WEAROS,
                    setOf(MarketTargetIds.CIV),
                    MarketHostVersionRange(1, 10),
                ),
            ),
        )

        assertTrue(MarketSecurityPolicy.hostCompatible(
            multiTarget, 100, MarketTargetIds.WEARTAK_TRACKER, setOf(MarketTargetIds.MIL),
        ))
        assertFalse(MarketSecurityPolicy.hostCompatible(
            multiTarget, 100, MarketTargetIds.WEARTAK_TRACKER, setOf(MarketTargetIds.CIV),
        ))
        assertFalse(MarketSecurityPolicy.hostCompatible(
            multiTarget, 100, MarketTargetIds.WEARTAK_WEAROS, setOf(MarketTargetIds.CIV),
        ))
        assertTrue(MarketSecurityPolicy.hostCompatible(
            multiTarget, 5, MarketTargetIds.WEARTAK_WEAROS, setOf(MarketTargetIds.CIV),
        ))
        assertFalse(MarketSecurityPolicy.hostCompatible(
            multiTarget, 5, MarketTargetIds.WEARTAK_TRACKER, setOf(MarketTargetIds.MIL),
        ))
    }

    @Test
    fun trackerBuildSupportsBothEditions() {
        val civ = plugin().copy(
            targets = listOf(
                MarketTarget(
                    MarketTargetIds.WEARTAK_TRACKER,
                    setOf(MarketTargetIds.CIV),
                    MarketHostVersionRange(90, 200),
                ),
            ),
        )
        val mil = plugin().copy(
            targets = listOf(
                MarketTarget(
                    MarketTargetIds.WEARTAK_TRACKER,
                    setOf(MarketTargetIds.MIL),
                    MarketHostVersionRange(90, 200),
                ),
            ),
        )

        assertTrue(MarketSecurityPolicy.hostCompatible(civ, 100))
        assertTrue(MarketSecurityPolicy.hostCompatible(mil, 100))
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
            state(
                release,
                100,
                installed,
                if (installed == null) null else setOf(signer),
                false,
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
        targets = listOf(
            MarketTarget(
                MarketTargetIds.WEARTAK_TRACKER,
                setOf(MarketTargetIds.MIL),
                MarketHostVersionRange(90, 200),
            ),
        ),
        apkUrl = "https://releases.example/weather.apk",
        apkSha256 = "b".repeat(64),
        signerSha256 = signer,
        sourceUrl = null,
        hostUpdate = hostUpdate,
    )

    private fun state(
        plugin: MarketPlugin,
        hostVersionCode: Long,
        installedVersionCode: Long?,
        installedSignerSha256: Set<String>?,
        playManagedHost: Boolean,
    ) = MarketSecurityPolicy.installState(
        plugin,
        hostPackage,
        hostVersionCode,
        installedVersionCode,
        installedSignerSha256,
        playManagedHost,
        MarketTargetIds.WEARTAK_TRACKER,
        setOf(MarketTargetIds.MIL),
    )
}
