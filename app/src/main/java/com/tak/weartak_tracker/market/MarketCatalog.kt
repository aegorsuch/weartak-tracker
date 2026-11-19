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
) {
    val isConfigured: Boolean
        get() = catalogUrl.isNotBlank() && catalogPublicKey.isNotBlank()

    companion object {
        fun fromBuildConfig() = MarketConfiguration(
            catalogUrl = BuildConfig.TAKWERX_MARKET_CATALOG_URL,
            catalogPublicKey = BuildConfig.TAKWERX_MARKET_CATALOG_PUBLIC_KEY,
        )
    }
}

internal enum class MarketPublisherType { OFFICIAL, COMMUNITY }

internal data class MarketPlugin(
    val id: String,
    val name: String,
    val description: String,
    val publisher: String,
    val publisherType: MarketPublisherType,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minHostVersionCode: Long,
    val maxHostVersionCode: Long?,
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
            if (catalog.getInt("schemaVersion") != 1) {
                throw MarketCatalogException("Catalog schema is not supported.")
            }
            val records = catalog.getJSONArray("plugins")
            if (records.length() > MAX_PLUGINS) {
                throw MarketCatalogException("Catalog contains too many plugins.")
            }

            val plugins = (0 until records.length()).map { index ->
                parsePlugin(records.getJSONObject(index), hostPackage)
            }
            if (plugins.map { it.id }.distinct().size != plugins.size ||
                plugins.map { it.packageName }.distinct().size != plugins.size
            ) {
                throw MarketCatalogException("Catalog contains duplicate plugin identifiers.")
            }
            return MarketCatalog(plugins)
        } catch (error: MarketCatalogException) {
            throw error
        } catch (error: Exception) {
            throw MarketCatalogException("Catalog format or signature is invalid.", error)
        }
    }

    private fun parsePlugin(record: JSONObject, hostPackage: String): MarketPlugin {
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
        if (packageName == hostPackage) {
            if (publisherType != MarketPublisherType.OFFICIAL || !hostUpdate) {
                throw MarketCatalogException("Only official host updates may target WearTAK-Tracker.")
            }
        } else if (hostUpdate) {
            throw MarketCatalogException("Catalog contains an update for a different host.")
        }

        val versionCode = record.getLong("versionCode")
        val minHostVersionCode = record.getLong("minHostVersionCode")
        val maxHostVersionCode = if (record.isNull("maxHostVersionCode")) null
        else record.getLong("maxHostVersionCode")
        if (versionCode <= 0 || minHostVersionCode <= 0 ||
            (maxHostVersionCode != null && maxHostVersionCode < minHostVersionCode)
        ) {
            throw MarketCatalogException("Catalog version range is invalid.")
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
            minHostVersionCode = minHostVersionCode,
            maxHostVersionCode = maxHostVersionCode,
            apkUrl = requireHttpsUrl(record.getString("apkUrl"), "APK URL"),
            apkSha256 = apkSha256,
            signerSha256 = signerSha256,
            sourceUrl = sourceUrl,
            hostUpdate = hostUpdate,
        )
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
    fun hostCompatible(plugin: MarketPlugin, hostVersionCode: Long): Boolean =
        hostVersionCode >= plugin.minHostVersionCode &&
            (plugin.maxHostVersionCode == null || hostVersionCode <= plugin.maxHostVersionCode)

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
    ): MarketInstallState {
        if (!hostCompatible(plugin, hostVersionCode)) return MarketInstallState.INCOMPATIBLE
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
