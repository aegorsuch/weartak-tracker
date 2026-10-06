package com.tak.weartak_tracker.transport

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tak.weartak_tracker.BuildConfig
import com.tak.weartak_tracker.data.TakServerConfig
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class EnrollmentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Client certificate handling, ported from WearTAK-CIV's TAKConnectService (sideload, cache, enroll). */
object TakCertificates {
    private const val TAG = "TakCertificates"
    const val STORE_PASSWORD = "atakatak"
    const val KEY_ALIAS = "myKeyAlias"
    const val ENROLLMENT_PORT = 8446
    private const val RENEW_BEFORE_MS = 3L * 24 * 60 * 60 * 1000

    data class Credentials(val keyStore: KeyStore, val password: CharArray, val source: String)

    fun cachedFile(context: Context, server: TakServerConfig): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(server.endpointKey.toByteArray())
        val name = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return File(context.filesDir, "tak_$name.jks")
    }

    fun deleteCached(context: Context, server: TakServerConfig) {
        cachedFile(context, server).delete()
    }

    /** Returns credentials from a sideloaded P12, a still-valid cached enrollment, or a fresh enrollment. */
    fun obtain(context: Context, server: TakServerConfig, deviceUid: String, onEnrolling: () -> Unit): Credentials {
        loadSideloaded(context, server)?.let { return it }
        val cached = cachedFile(context, server)
        loadCached(cached)?.let { return it }
        if (server.username.isBlank() || server.password.isEmpty()) {
            throw EnrollmentException(
                "No certificate: add username/password or sideload certs/${server.address.trim()}.p12 (see README)",
            )
        }
        onEnrolling()
        val ks = enroll(server, deviceUid)
        cached.parentFile?.mkdirs()
        val tmp = File(cached.parentFile, cached.name + ".tmp")
        tmp.outputStream().use { ks.store(it, STORE_PASSWORD.toCharArray()) }
        if (!tmp.renameTo(cached)) {
            tmp.copyTo(cached, overwrite = true)
            tmp.delete()
        }
        return Credentials(ks, STORE_PASSWORD.toCharArray(), "enrolled")
    }

    private fun sideloadDir(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "certs") }

    /** True when `certs/<address>.p12` or `.p12.b64` exists, so the server can connect without enrollment. */
    fun hasSideloaded(context: Context, address: String): Boolean {
        val dir = sideloadDir(context) ?: return false
        val a = address.trim()
        return a.isNotEmpty() && (File(dir, "$a.p12").isFile || File(dir, "$a.p12.b64").isFile)
    }

    private fun loadSideloaded(context: Context, server: TakServerConfig): Credentials? {
        val dir = sideloadDir(context) ?: return null
        val address = server.address.trim()
        val p12 = File(dir, "$address.p12")
        val b64 = File(dir, "$address.p12.b64")
        val bytes = when {
            p12.isFile -> p12.readBytes()
            b64.isFile -> Base64.decode(b64.readText().filterNot { it.isWhitespace() }, Base64.DEFAULT)
            else -> return null
        }
        val pwdFile = File(dir, "$address.pwd")
        val password = (if (pwdFile.isFile) pwdFile.readText().trim() else STORE_PASSWORD).toCharArray()
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(ByteArrayInputStream(bytes), password)
            Credentials(ks, password, "sideloaded")
        }.onFailure { Log.w(TAG, "Unable to load sideloaded P12 for $address", it) }.getOrNull()
    }

    private fun loadCached(file: File): Credentials? {
        if (!file.isFile) return null
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            file.inputStream().use { ks.load(it, STORE_PASSWORD.toCharArray()) }
            val leaf = ks.getCertificate(KEY_ALIAS) as? X509Certificate ?: return null
            if (leaf.notAfter.time - System.currentTimeMillis() < RENEW_BEFORE_MS) {
                Log.i(TAG, "Cached certificate expires soon; re-enrolling")
                return null
            }
            Credentials(ks, STORE_PASSWORD.toCharArray(), "cached")
        }.onFailure { Log.w(TAG, "Discarding unreadable cached keystore", it); file.delete() }.getOrNull()
    }

    private fun enroll(server: TakServerConfig, deviceUid: String): KeyStore {
        val host = server.address.trim()
        val auth = "Basic " + Base64.encodeToString(
            "${server.username}:${server.password}".toByteArray(), Base64.NO_WRAP,
        )
        val configXml = http("https://$host:$ENROLLMENT_PORT/Marti/api/tls/config", "GET", auth, null, null)
        val subject = mutableListOf("CN" to server.username)
        Regex("<nameEntry\\b[^>]*>").findAll(configXml).forEach { m ->
            val name = Regex("name=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1)
            val value = Regex("value=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1)
            if (!name.isNullOrBlank() && value != null) subject += name to value
        }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
        val csr = Base64.encodeToString(Csr.build(subject, keyPair), Base64.NO_WRAP)
        val version = URLEncoder.encode("WearTAK-Tracker v${BuildConfig.VERSION_NAME}", "UTF-8")
        val uid = URLEncoder.encode(deviceUid, "UTF-8")
        val response = http(
            "https://$host:$ENROLLMENT_PORT/Marti/api/tls/signClient/v2?clientUid=$uid&version=$version",
            "POST", auth, csr, "text/plain",
        )

        val (signed, cas) = parseSignResponse(response)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        cas.forEachIndexed { i, ca -> ks.setCertificateEntry("ca$i", ca) }
        ks.setKeyEntry(KEY_ALIAS, keyPair.private, STORE_PASSWORD.toCharArray(), (listOf(signed) + cas).toTypedArray())
        return ks
    }

    internal fun parseSignResponse(body: String): Pair<X509Certificate, List<X509Certificate>> {
        val trimmed = body.trim()
        val values: Map<String, String> = if (trimmed.startsWith("{")) {
            val o = JSONObject(trimmed)
            o.keys().asSequence().associateWith { o.optString(it) }
        } else {
            Regex("<(signedCert|ca\\d+)>([^<]*)</\\1>").findAll(trimmed)
                .associate { it.groupValues[1] to it.groupValues[2] }
        }
        val signed = values["signedCert"]?.takeIf { it.isNotBlank() }?.let(::parseCert)
            ?: throw EnrollmentException("Enrollment response did not contain a signed certificate")
        val cas = values.keys.filter { it.matches(Regex("ca\\d+")) }
            .sortedBy { it.removePrefix("ca").toInt() }
            .mapNotNull { values[it]?.takeIf(String::isNotBlank)?.let(::parseCert) }
        return signed to cas
    }

    private fun parseCert(text: String): X509Certificate {
        val b64 = text.replace(Regex("-----[A-Z ]+-----"), "").filterNot { it.isWhitespace() }
        val der = java.util.Base64.getDecoder().decode(b64)
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun http(url: String, method: String, auth: String, body: String?, contentType: String?): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", auth)
            conn.setRequestProperty("Accept", "application/json, application/xml;q=0.9, */*;q=0.8")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "text/plain")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw EnrollmentException(
                    if (code == HttpURLConnection.HTTP_UNAUTHORIZED) "Enrollment rejected: bad username/password"
                    else "Enrollment failed: HTTP $code",
                )
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: EnrollmentException) {
            throw e
        } catch (e: Exception) {
            throw EnrollmentException("Enrollment failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Socket factory whose trust manager also requires the server certificate to be issued for [host]
     * (or [savedName]); see [HostnameCheck.trustManager] for CA-scoped name discovery via [onDiscovered].
     */
    fun socketFactory(
        credentials: Credentials,
        host: String,
        savedName: String? = null,
        onDiscovered: ((String) -> Unit)? = null,
    ): SSLSocketFactory {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(credentials.keyStore, credentials.password)

        val anchors = mutableListOf<Certificate>()
        val ks = credentials.keyStore
        for (alias in ks.aliases()) {
            if (ks.isCertificateEntry(alias)) anchors += ks.getCertificate(alias)
            if (ks.isKeyEntry(alias)) {
                ks.getCertificateChain(alias)?.drop(1)?.let { anchors += it }
            }
        }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        anchors.distinct().forEachIndexed { i, c -> trustStore.setCertificateEntry("anchor$i", c) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(if (anchors.isEmpty()) ks else trustStore)

        val ctx = SSLContext.getInstance("TLS")
        val trustManagers = tmf.trustManagers.map { tm ->
            if (tm is X509TrustManager) HostnameCheck.trustManager(tm, host, savedName, onDiscovered) else tm
        }.toTypedArray()
        ctx.init(kmf.keyManagers, trustManagers, null)
        return ctx.socketFactory
    }
}
