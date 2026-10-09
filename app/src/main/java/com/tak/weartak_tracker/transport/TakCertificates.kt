package com.tak.weartak_tracker.transport

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tak.weartak_tracker.BuildConfig
import com.tak.weartak_tracker.R
import com.tak.weartak_tracker.data.TakServerConfig
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

enum class EnrollmentStage(val label: String) {
    CONFIG("Certificate configuration"),
    CSR("Certificate request"),
    SIGN("Certificate signing"),
    CERTIFICATE("Client certificate"),
    CONNECTION("TAK connection"),
}

enum class EnrollmentFailureCategory { AUTHENTICATION, HTTP, TLS, CONNECTIVITY, CERTIFICATE, CREDENTIALS }

data class EnrollmentFailure(
    val stage: EnrollmentStage,
    val category: EnrollmentFailureCategory,
    val httpCode: Int? = null,
) {
    /** Localized form of [displayMessage] for status shown in the UI. */
    fun displayMessage(context: Context): String {
        val stageText = context.getString(
            when (stage) {
                EnrollmentStage.CONFIG -> R.string.tak_enrollment_stage_config
                EnrollmentStage.CSR -> R.string.tak_enrollment_stage_csr
                EnrollmentStage.SIGN -> R.string.tak_enrollment_stage_sign
                EnrollmentStage.CERTIFICATE -> R.string.tak_enrollment_stage_certificate
                EnrollmentStage.CONNECTION -> R.string.tak_enrollment_stage_connection
            },
        )
        val detail = when (category) {
            EnrollmentFailureCategory.AUTHENTICATION -> context.getString(R.string.tak_enrollment_authentication_failed)
            EnrollmentFailureCategory.HTTP -> context.getString(R.string.tak_enrollment_http_failed, httpCode ?: 0)
            EnrollmentFailureCategory.TLS -> context.getString(R.string.tak_enrollment_tls_failed)
            EnrollmentFailureCategory.CONNECTIVITY -> context.getString(R.string.tak_enrollment_connectivity_failed)
            EnrollmentFailureCategory.CERTIFICATE -> context.getString(R.string.tak_enrollment_certificate_failed)
            EnrollmentFailureCategory.CREDENTIALS -> context.getString(R.string.tak_enrollment_credentials_missing)
        }
        return context.getString(R.string.tak_enrollment_failure, stageText, detail)
    }

    fun displayMessage(): String = "${stage.label}: " + when (category) {
        EnrollmentFailureCategory.AUTHENTICATION ->
            "authentication rejected (HTTP 401). Check username/password."
        EnrollmentFailureCategory.HTTP -> "server rejected request (HTTP $httpCode)."
        EnrollmentFailureCategory.TLS -> "secure connection failed. Check server certificate and hostname."
        EnrollmentFailureCategory.CONNECTIVITY -> "server unreachable. Check network and server address."
        EnrollmentFailureCategory.CERTIFICATE -> "invalid or missing certificate data."
        EnrollmentFailureCategory.CREDENTIALS ->
            "no client certificate. Add username/password or sideload a certificate."
    }

    companion object {
        fun from(stage: EnrollmentStage, error: Exception): EnrollmentFailure {
            val causes = generateSequence<Throwable>(error) { it.cause?.takeIf { cause -> cause !== it } }
                .take(16)
                .toList()
            val category = when {
                causes.any { it is SSLException } -> EnrollmentFailureCategory.TLS
                causes.any { it is IOException } -> EnrollmentFailureCategory.CONNECTIVITY
                else -> EnrollmentFailureCategory.CERTIFICATE
            }
            return EnrollmentFailure(stage, category)
        }
    }
}

class EnrollmentException(
    val failure: EnrollmentFailure,
    cause: Throwable? = null,
) : Exception(failure.displayMessage(), cause)

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
                EnrollmentFailure(EnrollmentStage.CERTIFICATE, EnrollmentFailureCategory.CREDENTIALS),
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
        val endpoint = "https://$host:$ENROLLMENT_PORT/Marti/api/tls"
        val (keyPair, response) = enrollmentSteps(
            config = {
                withStage(EnrollmentStage.CONFIG) {
                    val configXml = http("$endpoint/config", "GET", auth, null, null, EnrollmentStage.CONFIG)
                    mutableListOf("CN" to server.username).apply { addAll(parseConfig(configXml)) }
                }
            },
            csr = { subject ->
                withStage(EnrollmentStage.CSR) {
                    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
                    keyPair to Base64.encodeToString(Csr.build(subject, keyPair), Base64.NO_WRAP)
                }
            },
            sign = { (keyPair, csr) ->
                withStage(EnrollmentStage.SIGN) {
                    val version = URLEncoder.encode("WearTAK-Tracker v${BuildConfig.VERSION_NAME}", "UTF-8")
                    val uid = URLEncoder.encode(deviceUid, "UTF-8")
                    val response = http(
                        "$endpoint/signClient/v2?clientUid=$uid&version=$version",
                        "POST", auth, csr, "text/plain", EnrollmentStage.SIGN,
                    )
                    keyPair to response
                }
            },
        )

        return withStage(EnrollmentStage.CERTIFICATE) {
            val (signed, cas) = parseSignResponse(response)
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            cas.forEachIndexed { i, ca -> ks.setCertificateEntry("ca$i", ca) }
            ks.setKeyEntry(
                KEY_ALIAS,
                keyPair.private,
                STORE_PASSWORD.toCharArray(),
                (listOf(signed) + cas).toTypedArray(),
            )
            ks
        }
    }

    internal fun parseConfig(body: String): List<Pair<String, String>> = withStage(EnrollmentStage.CONFIG) {
        val document = parseXml(body)
        if ((document.documentElement.localName ?: document.documentElement.nodeName.substringAfter(':')) !=
            "certificateConfig"
        ) {
            throw IllegalArgumentException("Unexpected configuration document")
        }
        val entries = document.getElementsByTagNameNS("*", "nameEntry")
        (0 until entries.length).map { index ->
            val entry = entries.item(index) as Element
            val name = entry.getAttribute("name")
            if (name.isBlank() || !entry.hasAttribute("value")) {
                throw IllegalArgumentException("Invalid certificate name entry")
            }
            name to entry.getAttribute("value")
        }
    }

    internal fun parseSignResponse(body: String): Pair<X509Certificate, List<X509Certificate>> =
        withStage(EnrollmentStage.CERTIFICATE) {
            val text = withoutBom(body).trim()
            if (text.isEmpty()) throw IllegalArgumentException("Empty signing response")
            val values = if (text.startsWith("{")) {
                val json = JSONObject(text)
                json.keys().asSequence().associateWith { name ->
                    val value = json.get(name)
                    if (name == "signedCert" || CA_NAME.matches(name)) {
                        if (value !== JSONObject.NULL && value !is String) {
                            throw IllegalArgumentException("Certificate value must be text")
                        }
                    }
                    if (value is String) value else ""
                }
            } else {
                val document = parseXml(text)
                val elements = document.getElementsByTagName("*")
                buildMap {
                    for (index in 0 until elements.length) {
                        val element = elements.item(index) as Element
                        val name = element.localName ?: element.nodeName.substringAfter(':')
                        if (name == "signedCert" || CA_NAME.matches(name)) {
                            if (containsKey(name)) throw IllegalArgumentException("Duplicate certificate value")
                            put(name, element.textContent)
                        }
                    }
                }
            }
            val signedText = values["signedCert"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing signed certificate")
            val signed = parseCert(signedText)
            val cas = values.keys.filter(CA_NAME::matches)
                .sortedWith(CA_NAME_COMPARATOR)
                .mapNotNull { name -> values[name]?.takeIf(String::isNotBlank)?.let(::parseCert) }
            signed to cas
        }

    private fun parseCert(text: String): X509Certificate {
        val certificate = withoutBom(text).trim()
        val pemMatch = PEM_CERTIFICATE.matchEntire(certificate)
        val encoded = if (pemMatch != null) pemMatch.groupValues[1] else {
            if (certificate.contains("-----BEGIN") || certificate.contains("-----END")) {
                throw IllegalArgumentException("Invalid PEM certificate")
            }
            certificate
        }
        val der = java.util.Base64.getDecoder().decode(encoded.filterNot(Char::isWhitespace))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        if (!cert.encoded.contentEquals(der)) throw IllegalArgumentException("Trailing certificate data")
        return cert
    }

    private fun parseXml(xml: String) = run {
        val content = withoutBom(xml)
        if (XML_DECLARATION.containsMatchIn(content)) throw IllegalArgumentException("XML declarations are prohibited")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Android parsers differ; declaration rejection and the resolver remain mandatory.
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            ).forEach { (feature, enabled) ->
                try {
                    setFeature(feature, enabled)
                } catch (_: ParserConfigurationException) {
                    // These optional JAXP features are not supported by every Android parser.
                }
            }
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are prohibited") }
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) = throw e
                override fun fatalError(e: org.xml.sax.SAXParseException) = throw e
            })
        }.parse(InputSource(StringReader(content)))
    }

    private fun withoutBom(value: String): String =
        value.trimStart { it.isWhitespace() || it == '\uFEFF' }

    internal fun <C, R, S> enrollmentSteps(config: () -> C, csr: (C) -> R, sign: (R) -> S): S =
        sign(csr(config()))

    private inline fun <T> withStage(stage: EnrollmentStage, block: () -> T): T = try {
        block()
    } catch (e: EnrollmentException) {
        throw e
    } catch (e: Exception) {
        throw EnrollmentException(EnrollmentFailure.from(stage, e), e)
    }

    private fun http(
        url: String,
        method: String,
        auth: String,
        body: String?,
        contentType: String?,
        stage: EnrollmentStage,
    ): String {
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
            requireHttpSuccess(code, stage)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun requireHttpSuccess(code: Int, stage: EnrollmentStage) {
        if (code !in 200..299) {
            throw EnrollmentException(
                EnrollmentFailure(
                    stage,
                    if (code == HttpURLConnection.HTTP_UNAUTHORIZED) EnrollmentFailureCategory.AUTHENTICATION
                    else EnrollmentFailureCategory.HTTP,
                    code,
                ),
            )
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

        val anchors = selectTrustAnchors(credentials.keyStore)
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        anchors.distinct().forEachIndexed { i, c -> trustStore.setCertificateEntry("anchor$i", c) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)

        val ctx = SSLContext.getInstance("TLS")
        val trustManagers = tmf.trustManagers.map { tm ->
            if (tm is X509TrustManager) HostnameCheck.trustManager(tm, host, savedName, onDiscovered) else tm
        }.toTypedArray()
        ctx.init(kmf.keyManagers, trustManagers, null)
        return ctx.socketFactory
    }

    internal fun selectTrustAnchors(keyStore: KeyStore): List<X509Certificate> {
        val aliases = keyStore.aliases().toList()
        val keyEntries = aliases.filter(keyStore::isKeyEntry)
        val leafCertificates = keyEntries.mapNotNull { keyStore.getCertificate(it) as? X509Certificate }.toSet()
        val suppliedAuthorities = aliases.asSequence()
            .filter(keyStore::isCertificateEntry)
            .mapNotNull { keyStore.getCertificate(it) as? X509Certificate }
            .filter { it !in leafCertificates && it.basicConstraints >= 0 }
            .toList()
        val chainRoots = keyEntries.flatMap { alias ->
            val chain = keyStore.getCertificateChain(alias)
                ?.filterIsInstance<X509Certificate>()
                .orEmpty()
            val authorities = chain.drop(1).filter { it !in leafCertificates && it.basicConstraints >= 0 }
            listOfNotNull(
                authorities.firstOrNull(::isSelfSigned)
                    ?: authorities.firstOrNull { certificate ->
                        authorities.none { it !== certificate && it.subjectX500Principal == certificate.issuerX500Principal }
                    },
            )
        }
        return (suppliedAuthorities + chainRoots).distinct()
    }

    private fun isSelfSigned(certificate: X509Certificate): Boolean {
        if (certificate.subjectX500Principal != certificate.issuerX500Principal) return false
        return try {
            certificate.verify(certificate.publicKey)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    private val CA_NAME = Regex("ca[0-9]*")
    private val CA_NAME_COMPARATOR = Comparator<String> { first, second ->
        if (first == "ca" || second == "ca") {
            return@Comparator when {
                first == second -> 0
                first == "ca" -> -1
                else -> 1
            }
        }
        val firstNumber = first.substring(2).trimStart('0').ifEmpty { "0" }
        val secondNumber = second.substring(2).trimStart('0').ifEmpty { "0" }
        compareValues(firstNumber.length, secondNumber.length)
            .takeIf { it != 0 }
            ?: firstNumber.compareTo(secondNumber).takeIf { it != 0 }
            ?: first.compareTo(second)
    }
    private val PEM_CERTIFICATE = Regex(
        "-----BEGIN CERTIFICATE-----\\s*(.*?)\\s*-----END CERTIFICATE-----",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val XML_DECLARATION = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
}
