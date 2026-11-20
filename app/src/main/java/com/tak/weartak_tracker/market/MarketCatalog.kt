package com.tak.weartak_tracker.market

import com.tak.weartak_tracker.BuildConfig
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal data class MarketConfiguration(
    val catalogUrl: String,
    val catalogPublicKey: String,
    val productId: String = MarketTargetIds.WEARTAK_TRACKER,
    val editionIds: Set<String> = MarketTargetIds.editions,
) {
    val isConfigured: Boolean
        get() = catalogUrl.isNotBlank() && catalogPublicKey.isNotBlank()

    companion object {
        fun fromBuildConfig() = MarketConfiguration(
            catalogUrl = BuildConfig.TAKWERX_MARKET_CATALOG_URL,
            catalogPublicKey = BuildConfig.TAKWERX_MARKET_CATALOG_PUBLIC_KEY,
            productId = BuildConfig.TAKWERX_MARKET_PRODUCT_ID,
            editionIds = BuildConfig.TAKWERX_MARKET_EDITION_IDS
                .split(",")
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet(),
        )
    }
}

internal object MarketTargetIds {
    const val WEARTAK_WEAROS = "weartak-wearos"
    const val WEARTAK_TRACKER = "weartak-tracker"
    const val CIV = "civ"
    const val MIL = "mil"

    val products = setOf(WEARTAK_WEAROS, WEARTAK_TRACKER)
    val editions = setOf(CIV, MIL)
}

internal enum class MarketPublisherType { OFFICIAL, COMMUNITY }

internal data class MarketHostVersionRange(
    val minHostVersionCode: Long,
    val maxHostVersionCode: Long?,
)

internal data class MarketTarget(
    val productId: String,
    val editionIds: Set<String>,
    val hostVersionRange: MarketHostVersionRange,
    val legacy: Boolean = false,
)

internal data class MarketPlugin(
    val id: String,
    val name: String,
    val description: String,
    val publisher: String,
    val publisherType: MarketPublisherType,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val targets: List<MarketTarget>,
    val apkUrl: String,
    val apkSha256: String,
    val signerSha256: String,
    val sourceUrl: String?,
    val hostUpdate: Boolean,
)

internal data class MarketCatalog(val plugins: List<MarketPlugin>)

internal class MarketCatalogException(
    message: String,
    cause: Throwable? = null,
    val invalidCatalog: Boolean = true,
    val unsupportedTarget: Boolean = false,
) : Exception(message, cause)

internal object MarketCatalogParser {
    const val MAX_CATALOG_BYTES = 512 * 1024
    private const val MAX_PLUGINS = 200
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun parse(
        envelopeBytes: ByteArray,
        publicKeyBase64: String,
        hostPackage: String,
        hostProductId: String = MarketTargetIds.WEARTAK_TRACKER,
        hostEditionIds: Set<String> = MarketTargetIds.editions,
    ): MarketCatalog {
        if (envelopeBytes.isEmpty() || envelopeBytes.size > MAX_CATALOG_BYTES) {
            throw MarketCatalogException("Catalog exceeds size limit.")
        }

        try {
            val envelope = JSONObject(envelopeBytes.toString(Charsets.UTF_8))
            val payload = Base64.getMimeDecoder().decode(envelope.getString("payload"))
            val signatureBytes = Base64.getMimeDecoder().decode(envelope.getString("signature"))
            val publicKeyBytes = Base64.getMimeDecoder().decode(publicKeyBase64)
            val provider = EdDSASecurityProvider()
            val publicKey = KeyFactory.getInstance("EdDSA", provider)
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))
            val verifier = Signature.getInstance("NONEwithEdDSA", provider)
            verifier.initVerify(publicKey)
            verifier.update(payload)
            if (!verifier.verify(signatureBytes)) {
                throw MarketCatalogException("Catalog signature is invalid.")
            }

            val catalog = JSONObject(payload.toString(Charsets.UTF_8))
            val schemaVersion = catalog.getInt("schemaVersion")
            if (schemaVersion !in 1..2) {
                throw MarketCatalogException("Catalog schema is not supported.")
            }
            if (schemaVersion == 2 &&
                (hostProductId !in MarketTargetIds.products ||
                    hostEditionIds.isEmpty() || !MarketTargetIds.editions.containsAll(hostEditionIds))
            ) {
                throw MarketCatalogException(
                    "This build has no supported Market product editions configured.",
                    unsupportedTarget = true,
                )
            }
            val records = catalog.getJSONArray("plugins")
            if (records.length() > MAX_PLUGINS) {
                throw MarketCatalogException("Catalog contains too many plugins.")
            }

            val plugins = (0 until records.length()).map { index ->
                parsePlugin(
                    records.getJSONObject(index),
                    hostPackage,
                    hostProductId,
                    hostEditionIds,
                    schemaVersion,
                )
            }
            if (plugins.map { it.id }.distinct().size != plugins.size ||
                plugins.map { it.packageName }.distinct().size != plugins.size
            ) {
                throw MarketCatalogException("Catalog contains duplicate plugin identifiers.")
            }
            return MarketCatalog(plugins.filter { plugin ->
                plugin.targets.any { target ->
                    target.productId == hostProductId &&
                        (target.legacy || target.editionIds.any(hostEditionIds::contains))
                }
            })
        } catch (error: MarketCatalogException) {
            throw error
        } catch (error: Exception) {
            throw MarketCatalogException("Catalog format or signature is invalid.", error)
        }
    }

    private fun parsePlugin(
        record: JSONObject,
        hostPackage: String,
        hostProductId: String,
        hostEditionIds: Set<String>,
        schemaVersion: Int,
    ): MarketPlugin {
        val publisherType = when (record.getString("publisherType")) {
            "official" -> MarketPublisherType.OFFICIAL
            "community" -> MarketPublisherType.COMMUNITY
            else -> throw MarketCatalogException("Catalog publisher type is invalid.")
        }
        val packageName = record.getString("packageName")
        if (!packagePattern.matches(packageName)) {
            throw MarketCatalogException("Catalog package name is invalid.")
        }
        val hostUpdate = record.getBoolean("hostUpdate")
        val versionCode = record.getLong("versionCode")
        val targets = parseCompatibility(
            record,
            hostProductId,
            schemaVersion,
        )
        validateHostUpdate(
            packageName = packageName,
            hostPackage = hostPackage,
            publisherType = publisherType,
            hostUpdate = hostUpdate,
            targets = targets,
            hostProductId = hostProductId,
            hostEditionIds = hostEditionIds,
            schemaVersion = schemaVersion,
        )
        if (versionCode <= 0) {
            throw MarketCatalogException("Catalog version is invalid.")
        }

        val apkSha256 = record.getString("apkSha256").lowercase()
        val signerSha256 = record.getString("signerSha256").lowercase()
        if (!digestPattern.matches(apkSha256) || !digestPattern.matches(signerSha256)) {
            throw MarketCatalogException("Catalog digest is invalid.")
        }

        val sourceUrl = record.optString("sourceUrl").takeIf { it.isNotBlank() }
            ?.also { requireHttpsUrl(it, "source URL") }

        return MarketPlugin(
            id = requiredText(record, "id", 100),
            name = requiredText(record, "name", 100),
            description = requiredText(record, "description", 1000),
            publisher = requiredText(record, "publisher", 100),
            publisherType = publisherType,
            packageName = packageName,
            versionName = requiredText(record, "versionName", 80),
            versionCode = versionCode,
            targets = targets,
            apkUrl = requireHttpsUrl(record.getString("apkUrl"), "APK URL"),
            apkSha256 = apkSha256,
            signerSha256 = signerSha256,
            sourceUrl = sourceUrl,
            hostUpdate = hostUpdate,
        )
    }

    private fun parseCompatibility(
        record: JSONObject,
        hostProductId: String,
        schemaVersion: Int,
    ): List<MarketTarget> {
        if (schemaVersion == 1) {
            if (record.has("targets") || record.has("productIds") || record.has("editionIds") ||
                record.has("hostVersionRanges")
            ) {
                throw MarketCatalogException("Catalog compatibility metadata does not match schema.")
            }
            val range = versionRange(
                record.getLong("minHostVersionCode"),
                if (record.isNull("maxHostVersionCode")) null else record.getLong("maxHostVersionCode"),
            )
            return listOf(
                MarketTarget(
                    productId = hostProductId,
                    editionIds = MarketTargetIds.editions,
                    hostVersionRange = range,
                    legacy = true,
                ),
            )
        }

        if (record.has("minHostVersionCode") || record.has("maxHostVersionCode")) {
            throw MarketCatalogException("Catalog contains legacy host version fields.")
        }
        val targets = record.getJSONArray("targets")
        val parsedTargets = (0 until targets.length()).map { index ->
            val target = targets.getJSONObject(index)
            val targetFields = mutableSetOf<String>()
            val keys = target.keys()
            while (keys.hasNext()) {
                targetFields += keys.next()
            }
            if (targetFields != setOf(
                    "productId",
                    "editionIds",
                    "minHostVersionCode",
                    "maxHostVersionCode",
                )
            ) {
                throw MarketCatalogException("Catalog target fields are invalid.")
            }
            val productId = target.getString("productId")
            if (productId !in MarketTargetIds.products) {
                throw MarketCatalogException("Catalog target product is invalid.")
            }
            val editionIds = parseIds(
                target.getJSONArray("editionIds"),
                "editionIds",
                MarketTargetIds.editions,
            )
            if (!target.has("minHostVersionCode") || !target.has("maxHostVersionCode")) {
                throw MarketCatalogException("Catalog host version range is incomplete.")
            }
            MarketTarget(
                productId = productId,
                editionIds = editionIds,
                hostVersionRange = versionRange(
                    target.getLong("minHostVersionCode"),
                    if (target.isNull("maxHostVersionCode")) null
                    else target.getLong("maxHostVersionCode"),
                ),
            )
        }
        if (parsedTargets.isEmpty() ||
            parsedTargets.map { it.productId }.distinct().size != parsedTargets.size
        ) {
            throw MarketCatalogException("Catalog targets are missing or duplicated.")
        }
        return parsedTargets
    }

    private fun parseIds(
        values: org.json.JSONArray,
        field: String,
        allowedIds: Set<String>,
    ): Set<String> {
        val ids = (0 until values.length()).map { values.getString(it) }
        if (ids.isEmpty() || ids.any { it !in allowedIds } || ids.distinct().size != ids.size) {
            throw MarketCatalogException("Catalog field '$field' is invalid.")
        }
        return ids.toSet()
    }

    private fun versionRange(min: Long, max: Long?): MarketHostVersionRange {
        if (min <= 0 || (max != null && max < min)) {
            throw MarketCatalogException("Catalog version range is invalid.")
        }
        return MarketHostVersionRange(min, max)
    }

    private fun validateHostUpdate(
        packageName: String,
        hostPackage: String,
        publisherType: MarketPublisherType,
        hostUpdate: Boolean,
        targets: List<MarketTarget>,
        hostProductId: String,
        hostEditionIds: Set<String>,
        schemaVersion: Int,
    ) {
        if (schemaVersion == 1) {
            if (packageName == hostPackage) {
                if (publisherType != MarketPublisherType.OFFICIAL || !hostUpdate) {
                    throw MarketCatalogException("Only official host updates may target WearTAK-Tracker.")
                }
            } else if (hostUpdate) {
                throw MarketCatalogException("Catalog contains an update for a different host.")
            }
            return
        }

        val targetsThisProduct = targets.any { it.productId == hostProductId }
        if (hostUpdate && publisherType != MarketPublisherType.OFFICIAL) {
            throw MarketCatalogException("Only official packages may be marked as host updates.")
        }
        if (packageName == hostPackage &&
            (publisherType != MarketPublisherType.OFFICIAL || !hostUpdate || !targetsThisProduct)
        ) {
            throw MarketCatalogException("Host packages must be official host updates for this product.")
        }
        val targetsThisHost = targets.any {
            it.productId == hostProductId && it.editionIds.any(hostEditionIds::contains)
        }
        if (hostUpdate && targetsThisHost && packageName != hostPackage) {
            throw MarketCatalogException("Catalog host update package does not match this host.")
        }
    }

    private fun requiredText(record: JSONObject, key: String, maxLength: Int): String {
        val value = record.getString(key).trim()
        if (value.isBlank() || value.length > maxLength) {
            throw MarketCatalogException("Catalog field '$key' is invalid.")
        }
        return value
    }

    internal fun requireHttpsUrl(value: String, field: String): String {
        val url = value.toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null
        ) {
            throw MarketCatalogException("Catalog $field must be an HTTPS URL.")
        }
        return url.toString()
    }
}

internal enum class MarketInstallState {
    INSTALL,
    UPDATE,
    UP_TO_DATE,
    NEWER_VERSION_INSTALLED,
    INCOMPATIBLE,
    SIGNER_MISMATCH,
    PLAY_MANAGED_HOST,
    PACKAGE_QUERY_FAILED,
}

internal object MarketSecurityPolicy {
    fun hostCompatible(
        plugin: MarketPlugin,
        hostVersionCode: Long,
        hostProductId: String = MarketTargetIds.WEARTAK_TRACKER,
        hostEditionIds: Set<String> = MarketTargetIds.editions,
    ): Boolean {
        val target = plugin.targets.singleOrNull { it.productId == hostProductId } ?: return false
        if (!target.legacy && target.editionIds.intersect(hostEditionIds).isEmpty()) return false
        val range = target.hostVersionRange
        return hostVersionCode >= range.minHostVersionCode &&
            (range.maxHostVersionCode == null || hostVersionCode <= range.maxHostVersionCode)
    }

    fun archiveMatches(
        plugin: MarketPlugin,
        packageName: String?,
        versionCode: Long,
        signerSha256: Set<String>,
    ): Boolean =
        packageName == plugin.packageName &&
            versionCode == plugin.versionCode &&
            signerSha256 == setOf(plugin.signerSha256)

    fun installState(
        plugin: MarketPlugin,
        hostPackage: String,
        hostVersionCode: Long,
        installedVersionCode: Long?,
        installedSignerSha256: Set<String>?,
        playManagedHost: Boolean,
        hostProductId: String = MarketTargetIds.WEARTAK_TRACKER,
        hostEditionIds: Set<String> = MarketTargetIds.editions,
    ): MarketInstallState {
        if (!hostCompatible(plugin, hostVersionCode, hostProductId, hostEditionIds)) {
            return MarketInstallState.INCOMPATIBLE
        }
        if (plugin.packageName == hostPackage && playManagedHost) {
            return MarketInstallState.PLAY_MANAGED_HOST
        }
        if (installedVersionCode != null &&
            installedSignerSha256 != setOf(plugin.signerSha256)
        ) {
            return MarketInstallState.SIGNER_MISMATCH
        }
        return when {
            installedVersionCode == null -> MarketInstallState.INSTALL
            installedVersionCode < plugin.versionCode -> MarketInstallState.UPDATE
            installedVersionCode == plugin.versionCode -> MarketInstallState.UP_TO_DATE
            else -> MarketInstallState.NEWER_VERSION_INSTALLED
        }
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
