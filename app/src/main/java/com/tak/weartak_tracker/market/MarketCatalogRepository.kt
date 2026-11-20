package com.tak.weartak_tracker.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class MarketCatalogRepository(
    private val configuration: MarketConfiguration,
    client: OkHttpClient = OkHttpClient(),
) {
    val hostProductId: String
        get() = configuration.productId
    val hostEditionIds: Set<String>
        get() = configuration.editionIds

    private val client = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val downloadClient = this.client.newBuilder()
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    suspend fun fetchCatalog(hostPackage: String): MarketCatalog = withContext(Dispatchers.IO) {
        if (!configuration.isConfigured) {
            throw MarketCatalogException("TAKwerx Market catalog is not configured for this build.")
        }
        val url = MarketCatalogParser.requireHttpsUrl(configuration.catalogUrl, "catalog URL")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw MarketCatalogException(
                    "Catalog request failed (HTTP ${response.code}).",
                    invalidCatalog = false,
                )
            }
            val body = response.body ?: throw MarketCatalogException(
                "Catalog response is empty.",
                invalidCatalog = false,
            )
            if (body.contentLength() > MarketCatalogParser.MAX_CATALOG_BYTES) {
                throw MarketCatalogException("Catalog exceeds size limit.")
            }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MarketCatalogParser.MAX_CATALOG_BYTES) {
                        throw MarketCatalogException("Catalog exceeds size limit.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            MarketCatalogParser.parse(
                output.toByteArray(),
                configuration.catalogPublicKey,
                hostPackage,
                configuration.productId,
                configuration.editionIds,
            )
        }
    }

    suspend fun downloadVerified(plugin: MarketPlugin, cacheDirectory: File): File =
        withContext(Dispatchers.IO) {
            val url = MarketCatalogParser.requireHttpsUrl(plugin.apkUrl, "APK URL")
            val request = Request.Builder().url(url).get().build()
            val file = File.createTempFile("takwerx-market-", ".apk", cacheDirectory)
            try {
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw MarketCatalogException("Plugin download failed (HTTP ${response.code}).")
                    }
                    val body = response.body ?: throw MarketCatalogException("Plugin download is empty.")
                    if (body.contentLength() > MAX_APK_BYTES) {
                        throw MarketCatalogException("Plugin APK exceeds download limit.")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(file).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_APK_BYTES) {
                                    throw MarketCatalogException("Plugin APK exceeds download limit.")
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    val actualDigest = digest.digest()
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (actualDigest != plugin.apkSha256) {
                        throw MarketCatalogException("Plugin APK checksum does not match catalog.")
                    }
                }
                currentCoroutineContext().ensureActive()
                file
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    companion object {
        const val MAX_APK_BYTES = 250L * 1024 * 1024
    }
}
