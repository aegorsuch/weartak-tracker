package com.tak.weartak_tracker.transport

import java.net.IDN
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Thrown from the trust manager, so it aborts the handshake before the client certificate is sent. */
class HostnameMismatchException(message: String) : CertificateException(message)

/** The hostname mismatch that caused this handshake failure, if any (trust-manager errors arrive wrapped). */
fun SSLException.hostnameMismatch(): HostnameMismatchException? =
    generateSequence<Throwable>(this) { t -> t.cause?.takeIf { it !== t } }
        .take(10)
        .filterIsInstance<HostnameMismatchException>()
        .firstOrNull()

/**
 * Checks that a TAK Server certificate was issued for the address the user configured.
 * Hostnames match DNS subjectAltNames (with a single left-most `*` label); IP addresses match only IP
 * subjectAltNames. Many TAK Server certificates have no SANs, so the subject CN is used only when the
 * certificate has no subjectAltName of any type.
 */
object HostnameCheck {
    private const val SAN_DNS = 2
    private const val SAN_IP = 7

    fun verify(host: String, cert: X509Certificate) {
        val sans = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
        val dns = sans.filter { it.getOrNull(0) == SAN_DNS }.mapNotNull { it.getOrNull(1) as? String }
        val ips = sans.filter { it.getOrNull(0) == SAN_IP }.mapNotNull { it.getOrNull(1) as? String }
        val cn = commonName(cert.subjectX500Principal.getName("RFC2253"))
        if (!matches(host, dns, ips, cn, cnFallback = sans.isEmpty())) {
            val names = (dns + ips).ifEmpty { listOfNotNull(cn.takeIf { sans.isEmpty() }) }.joinToString()
            throw HostnameMismatchException("Certificate is for [$names], not $host")
        }
    }

    fun matches(
        host: String,
        dnsNames: List<String>,
        ipAddresses: List<String>,
        commonName: String?,
        cnFallback: Boolean = dnsNames.isEmpty() && ipAddresses.isEmpty(),
    ): Boolean {
        val target = normalize(host)
        if (target.isEmpty()) return false
        val fallback = if (cnFallback) listOfNotNull(commonName) else emptyList()
        val ip = ipBytes(target)
        if (ip != null) return (ipAddresses + fallback).any { ipBytes(normalize(it))?.contentEquals(ip) == true }
        return (dnsNames + fallback).any { dnsMatches(target, normalize(it)) }
    }

    /**
     * Wraps [delegate] so the server's chain must be trusted *and* issued for [host] (or for the
     * previously discovered [savedName]).
     *
     * When [onDiscovered] is set and nothing has been saved yet, a strict mismatch may fall back to
     * WearTAK-iOS/CIV's CA-scoped discovery (see [discoverName]); the discovered name is reported so the
     * caller can persist it. A saved name is never replaced automatically.
     */
    fun trustManager(
        delegate: X509TrustManager,
        host: String,
        savedName: String? = null,
        onDiscovered: ((String) -> Unit)? = null,
        publicTrust: () -> X509TrustManager? = ::systemTrustManager,
    ): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            delegate.checkServerTrusted(chain, authType)
            val leaf = chain.firstOrNull() ?: throw CertificateException("Empty server certificate chain")
            val strict = try {
                verify(host, leaf)
                return
            } catch (e: HostnameMismatchException) {
                e
            }
            val saved = savedName?.takeIf { it.isNotBlank() }
            if (saved != null) {
                runCatching { verify(saved, leaf) }.onSuccess { return }
                throw HostnameMismatchException("${strict.message} or saved TLS name $saved")
            }
            if (onDiscovered == null) throw strict
            when (val result = discoverName(chain, authType, publicTrust())) {
                is Discovery.Found -> onDiscovered(result.name)
                is Discovery.Rejected -> throw HostnameMismatchException("${strict.message} (${result.reason})")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    sealed interface Discovery {
        data class Found(val name: String) : Discovery
        data class Rejected(val reason: String) : Discovery
    }

    /**
     * Picks the server's TLS name for legacy private-CA deployments addressed by a different host/IP.
     * Only valid when the (already trusted) chain is *not* trusted by public CAs and the leaf has exactly
     * one exact, non-wildcard DNS subjectAltName. Multiple names, wildcards-only, no SANs, or a public CA
     * are rejected so a public certificate can never be used to impersonate a configured server.
     */
    fun discoverName(chain: Array<out X509Certificate>, authType: String, publicTrust: X509TrustManager?): Discovery {
        val leaf = chain.firstOrNull() ?: return Discovery.Rejected("empty chain")
        if (publicTrust != null && runCatching { publicTrust.checkServerTrusted(chain, authType) }.isSuccess) {
            return Discovery.Rejected("publicly trusted certificate")
        }
        val sans = runCatching { leaf.subjectAlternativeNames }.getOrNull().orEmpty()
        val names = sans.filter { it.getOrNull(0) == SAN_DNS }
            .mapNotNull { (it.getOrNull(1) as? String)?.let(::normalizeDnsName) }
            .distinct()
        return when {
            names.isEmpty() -> Discovery.Rejected("no exact DNS name to discover")
            names.size > 1 -> Discovery.Rejected("multiple DNS names: ${names.joinToString()}")
            runCatching { verify(names[0], leaf) }.isFailure -> Discovery.Rejected("name ${names[0]} not verifiable")
            else -> Discovery.Found(names[0])
        }
    }

    /** An exact DNS name in ASCII form, or null for wildcards, IP literals, and malformed names. */
    internal fun normalizeDnsName(raw: String): String? {
        val n = normalize(raw)
        if (n.isEmpty() || n.contains('*') || n.contains(':') || ipBytes(n) != null) return null
        val ascii = runCatching { IDN.toASCII(n, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }.getOrNull()
        return ascii?.takeIf { it.isNotEmpty() && it.split('.').none(String::isEmpty) }
    }

    private fun systemTrustManager(): X509TrustManager? = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }.getOrNull()

    internal fun isIpLiteral(host: String): Boolean = ipBytes(normalize(host)) != null

    private fun dnsMatches(host: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        if (!pattern.startsWith("*.")) return host == pattern
        val suffix = pattern.substring(1)
        // `*` covers exactly one label and never a bare public suffix like `*.com`.
        if (suffix.count { it == '.' } < 2) return false
        return host.endsWith(suffix) && host.length > suffix.length && host.indexOf('.') == host.length - suffix.length
    }

    private fun normalize(s: String) = s.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val IPV6 = Regex("""^[0-9a-f:.]+$""")

    /** Parses an IP literal without DNS lookups; null for hostnames. */
    private fun ipBytes(s: String): ByteArray? {
        IPV4.matchEntire(s)?.let { m ->
            val octets = m.groupValues.drop(1).map { it.toInt() }
            return if (octets.all { it in 0..255 }) octets.map { it.toByte() }.toByteArray() else null
        }
        if (s.contains(':') && IPV6.matches(s)) return runCatching { InetAddress.getByName(s).address }.getOrNull()
        return null
    }

    internal fun commonName(rfc2253: String): String? {
        var i = 0
        while (i < rfc2253.length) {
            val start = i
            val value = StringBuilder()
            var escaped = false
            while (i < rfc2253.length) {
                val c = rfc2253[i]
                if (escaped) { value.append(c); escaped = false } else when (c) {
                    '\\' -> escaped = true
                    ',', '+' -> break
                    else -> value.append(c)
                }
                i++
            }
            i++
            val rdn = value.toString()
            val eq = rdn.indexOf('=')
            if (eq > 0 && rdn.substring(0, eq).trim().equals("CN", ignoreCase = true)) return rdn.substring(eq + 1).trim()
            if (start == i) break
        }
        return null
    }
}
