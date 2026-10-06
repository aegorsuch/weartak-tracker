package com.tak.weartak_tracker

import com.tak.weartak_tracker.transport.Csr
import com.tak.weartak_tracker.transport.HostnameCheck
import com.tak.weartak_tracker.transport.HostnameMismatchException
import com.tak.weartak_tracker.transport.TakCertificates
import com.tak.weartak_tracker.transport.TakServerManager
import com.tak.weartak_tracker.transport.hostnameMismatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

class TlsTest {
    private fun pem(body: String) = "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----"

    private fun cert(body: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(pem(body).toByteArray())) as X509Certificate

    /** CN=tak.example.com; SAN dns:tak.example.com, dns:*.ops.example.com, ip:10.0.0.5 (test-only, self-signed). */
    private val sanCertBody = """
        MIIBojCCAUegAwIBAgIJAI0IPDdEF6j8MAoGCCqGSM49BAMCMCkxDTALBgNVBAoT
        BFRlc3QxGDAWBgNVBAMTD3Rhay5leGFtcGxlLmNvbTAgFw0yNjEwMDYxNzI0MzNa
        GA8yMTI2MDkxMjE3MjQzM1owKTENMAsGA1UEChMEVGVzdDEYMBYGA1UEAxMPdGFr
        LmV4YW1wbGUuY29tMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEU3y4JGtJYgBz
        JQiBXbENAUwScaKTnpeT4fppfGf7dNoqEqDWCuSua+6L4iIDx9x/rrnVsWFMehfs
        SyS56t3XCKNWMFQwHQYDVR0OBBYEFM2Q/WlPpDZzV9D9nidpHotMVZFMMDMGA1Ud
        EQQsMCqCD3Rhay5leGFtcGxlLmNvbYIRKi5vcHMuZXhhbXBsZS5jb22HBAoAAAUw
        CgYIKoZIzj0EAwIDSQAwRgIhAMjDwDmwbPymD3NFFEZEsaeav9dnaakeXaBrvNWU
        HlgwAiEAtSPAu5kEoN805U4mmvSda2rIWXBN+BMx2bKt8I9k2rY=
    """.trimIndent()

    /** CN=192.168.1.20, OU=TAK, O=Test; no SANs, like many TAK Server certificates (test-only, self-signed). */
    private val cnCertBody = """
        MIIBgjCCASigAwIBAgIJAOnondmSNHesMAoGCCqGSM49BAMCMDQxDTALBgNVBAoT
        BFRlc3QxDDAKBgNVBAsTA1RBSzEVMBMGA1UEAxMMMTkyLjE2OC4xLjIwMCAXDTI2
        MTAwNjE3MjQzNFoYDzIxMjYwOTEyMTcyNDM0WjA0MQ0wCwYDVQQKEwRUZXN0MQww
        CgYDVQQLEwNUQUsxFTATBgNVBAMTDDE5Mi4xNjguMS4yMDBZMBMGByqGSM49AgEG
        CCqGSM49AwEHA0IABIFE949TdFgw2T4Cz5MDZ5csKHxb5w4POeZBI+vycELrjL6Y
        CR39n2bxccg+a921bLiPiNrwHZbAhBFQeoTHGLWjITAfMB0GA1UdDgQWBBRPDI7P
        kKJz0TV8p/KQ7zqHRF6E2TAKBggqhkjOPQQDAgNIADBFAiEAwUEy0ndrT0sP5Vdi
        r+MSWL0yKxLLmU+AqrtVlZ0iDy4CIFFGwEiz/T7S3Be6bSlzpO3arI2/qiVwT03/
        7VLjK36N
    """.trimIndent()

    private fun verifies(host: String, c: X509Certificate) =
        runCatching { HostnameCheck.verify(host, c) }.isSuccess

    @Test
    fun sanCertificateMatchesDnsWildcardAndIp() {
        val c = cert(sanCertBody)
        assertTrue(verifies("tak.example.com", c))
        assertTrue(verifies("TAK.Example.com.", c))
        assertTrue(verifies("a.ops.example.com", c))
        assertTrue(verifies("10.0.0.5", c))
        assertFalse(verifies("b.a.ops.example.com", c))
        assertFalse(verifies("ops.example.com", c))
        assertFalse(verifies("evil.example.com", c))
        assertFalse(verifies("10.0.0.6", c))
    }

    @Test
    fun cnOnlyCertificateFallsBackToCommonName() {
        val c = cert(cnCertBody)
        assertTrue(verifies("192.168.1.20", c))
        assertFalse(verifies("192.168.1.21", c))
        assertFalse(verifies("takserver.local", c))
        val error = runCatching { HostnameCheck.verify("192.168.1.21", c) }.exceptionOrNull()
        assertTrue(error is HostnameMismatchException)
        assertTrue(error!!.message!!.contains("192.168.1.20"))
    }

    @Test
    fun commonNameIsIgnoredWhenSansExist() {
        assertFalse(HostnameCheck.matches("tak.example.com", listOf("other.example.com"), emptyList(), "tak.example.com"))
        assertFalse(HostnameCheck.matches("10.0.0.1", emptyList(), listOf("10.0.0.2"), "10.0.0.1"))
        // Only other SAN types (e.g. URI) present: still no CN fallback.
        assertFalse(HostnameCheck.matches("tak.example.com", emptyList(), emptyList(), "tak.example.com", cnFallback = false))
    }

    @Test
    fun ipMatchingNormalizesAndRejectsPublicSuffixWildcards() {
        assertTrue(HostnameCheck.matches("[::1]", emptyList(), listOf("0:0:0:0:0:0:0:1"), null))
        // An IP connection must be backed by an IP SAN, not a DNS SAN spelled like an IP.
        assertFalse(HostnameCheck.matches("10.0.0.5", listOf("10.0.0.5"), emptyList(), null))
        assertFalse(HostnameCheck.matches("10.0.0.5", listOf("*.0.0.5"), emptyList(), null))
        assertFalse(HostnameCheck.matches("example.com", listOf("*.com"), emptyList(), null))
        assertFalse(HostnameCheck.matches("", listOf(""), emptyList(), ""))
    }

    @Test
    fun trustManagerRejectsWrongHostDuringHandshake() {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val chain = arrayOf(cert(sanCertBody))
        HostnameCheck.trustManager(trustAll, "tak.example.com").checkServerTrusted(chain, "ECDHE_ECDSA")
        val error = runCatching {
            HostnameCheck.trustManager(trustAll, "evil.example.com").checkServerTrusted(chain, "ECDHE_ECDSA")
        }.exceptionOrNull()
        assertTrue(error is HostnameMismatchException)

        // The TLS stack wraps trust-manager failures; the mismatch must still be recognisable.
        val wrapped = SSLHandshakeException("handshake failed").apply { initCause(RuntimeException(error)) }
        assertSame(error, wrapped.hostnameMismatch())
        assertNull(SSLHandshakeException("other").hostnameMismatch())
    }

    @Test
    fun untrustedChainIsRejectedBeforeHostCheck() {
        val rejectAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
                throw CertificateException("untrusted")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val error = runCatching {
            HostnameCheck.trustManager(rejectAll, "tak.example.com").checkServerTrusted(arrayOf(cert(sanCertBody)), "RSA")
        }.exceptionOrNull()
        assertTrue(error is CertificateException && error !is HostnameMismatchException)
    }

    /** SAN dns:a.example.com, dns:b.example.com (test-only, self-signed). */
    private val multiSanCertBody = """
        MIIBYjCCAQmgAwIBAgIJAOZ8EDQvJHC+MAoGCCqGSM49BAMCMBAxDjAMBgNVBAMT
        BW11bHRpMCAXDTI2MTAwNjE4MTAyMFoYDzIxMjYwOTEyMTgxMDIwWjAQMQ4wDAYD
        VQQDEwVtdWx0aTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABJ6YGhC37Kme2cSP
        5ahuV6ovQxb9hZe3VaWTxB8Jb7BFDp/ajgo8G0/T/5XnOyDfIXQEN8HnlRL0ePWI
        kwEBYM+jSjBIMB0GA1UdDgQWBBS9dWAxwPrFcmDAynuAntgXYoBOMjAnBgNVHREE
        IDAegg1hLmV4YW1wbGUuY29tgg1iLmV4YW1wbGUuY29tMAoGCCqGSM49BAMCA0cA
        MEQCIDSa2KddHZ0XZms5gTmnmjNiFxEIa5ABCKhhdKHVZDItAiAz21nZ2u3j1I/C
        Avp3r78CnOyycrwaAAiJzPp7m1eySA==
    """.trimIndent()

    private fun trusting(trusted: Boolean) = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (!trusted) throw CertificateException("untrusted")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun discover(body: String, publiclyTrusted: Boolean = false) =
        HostnameCheck.discoverName(arrayOf(cert(body)), "ECDHE_ECDSA", trusting(publiclyTrusted))

    @Test
    fun discoveryNeedsOneExactDnsNameFromPrivateCa() {
        // Wildcard and IP SANs are ignored; the single exact DNS SAN is discovered.
        assertEquals(HostnameCheck.Discovery.Found("tak.example.com"), discover(sanCertBody))
        assertTrue(discover(sanCertBody, publiclyTrusted = true) is HostnameCheck.Discovery.Rejected)
        assertTrue(discover(multiSanCertBody) is HostnameCheck.Discovery.Rejected)
        assertTrue(discover(cnCertBody) is HostnameCheck.Discovery.Rejected)
        assertNull(HostnameCheck.normalizeDnsName("*.example.com"))
        assertNull(HostnameCheck.normalizeDnsName("10.0.0.5"))
        assertEquals("tak.example.com", HostnameCheck.normalizeDnsName("TAK.example.com."))
    }

    @Test
    fun trustManagerDiscoversSavesAndNeverReplacesNames() {
        val chain = arrayOf(cert(sanCertBody))
        val found = mutableListOf<String>()
        // Connected by an IP that isn't in the certificate: discovery accepts and reports the name.
        HostnameCheck.trustManager(trusting(true), "192.168.50.1", null, { found += it }) { trusting(false) }
            .checkServerTrusted(chain, "ECDHE_ECDSA")
        assertEquals(listOf("tak.example.com"), found)

        // Without a discovery callback the strict check still applies.
        assertTrue(
            runCatching { HostnameCheck.trustManager(trusting(true), "192.168.50.1").checkServerTrusted(chain, "EC") }
                .exceptionOrNull() is HostnameMismatchException,
        )

        // A saved name is used as-is and a mismatch does not trigger rediscovery.
        HostnameCheck.trustManager(trusting(true), "192.168.50.1", "tak.example.com").checkServerTrusted(chain, "EC")
        val error = runCatching {
            HostnameCheck.trustManager(trusting(true), "192.168.50.1", "old.example.com", { found += it }) { trusting(false) }
                .checkServerTrusted(chain, "EC")
        }.exceptionOrNull()
        assertTrue(error is HostnameMismatchException)
        assertEquals(1, found.size)

        // Publicly trusted certificates are never used for discovery.
        val publicError = runCatching {
            HostnameCheck.trustManager(trusting(true), "192.168.50.1", null, { found += it }) { trusting(true) }
                .checkServerTrusted(chain, "EC")
        }.exceptionOrNull()
        assertTrue(publicError is HostnameMismatchException)
        assertEquals(1, found.size)
    }

    @Test
    fun commonNameParsingHandlesEscapes() {
        assertEquals("a,b", HostnameCheck.commonName("O=Test,CN=a\\,b"))
        assertEquals("tak", HostnameCheck.commonName("CN=tak+OU=x,O=y"))
        assertNull(HostnameCheck.commonName("O=Test,OU=TAK"))
    }

    @Test
    fun signResponseJsonAndXml() {
        val json = """{"signedCert":"$sanCertBody","ca0":"$cnCertBody"}""".replace("\n", "")
        val (signed, cas) = TakCertificates.parseSignResponse(json)
        assertEquals(cert(sanCertBody), signed)
        assertEquals(listOf(cert(cnCertBody)), cas)

        val xml = "<enrollment><signedCert>${pem(cnCertBody)}</signedCert><ca1>$sanCertBody</ca1><ca0>$cnCertBody</ca0></enrollment>"
        val (signed2, cas2) = TakCertificates.parseSignResponse(xml)
        assertEquals(cert(cnCertBody), signed2)
        assertEquals(listOf(cert(cnCertBody), cert(sanCertBody)), cas2)
    }

    @Test
    fun signResponseWithoutCertificateFails() {
        assertTrue(runCatching { TakCertificates.parseSignResponse("{}") }.isFailure)
    }

    @Test
    fun csrIsSignedAndEncodesOids() {
        assertArrayEquals(
            byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B),
            Csr.oid("1.2.840.113549.1.1.11"),
        )
        assertArrayEquals(byteArrayOf(0x04, 0x82.toByte(), 0x01, 0x00), Csr.tlv(0x04, ByteArray(256)).copyOf(4))

        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val der = Csr.build(listOf("CN" to "user", "O" to "TAK", "OU" to "Ops"), keys)
        // CertificationRequest ::= SEQUENCE { info, algorithm, signature BIT STRING }
        val outer = Der(der).enter()
        val infoStart = outer.pos
        outer.skip()
        val info = der.copyOfRange(infoStart, outer.pos)
        outer.skip()
        val sig = outer.read(0x03).drop(1).toByteArray()
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(keys.public)
            update(info)
            verify(sig)
        }
        assertTrue(ok)
        assertTrue(runCatching { Csr.name(listOf("BOGUS" to "x")) }.isFailure)
    }

    @Test
    fun reconnectBackoffDoublesAndCaps() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
            (1..8).map { TakServerManager.backoffMillis(it) })
    }

    /** Minimal DER reader for the CSR test. */
    private class Der(val bytes: ByteArray, var pos: Int = 0) {
        private fun header(): Pair<Int, Int> {
            val tag = bytes[pos++].toInt() and 0xFF
            var len = bytes[pos++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                len = 0
                repeat(n) { len = (len shl 8) or (bytes[pos++].toInt() and 0xFF) }
            }
            return tag to len
        }

        fun enter(): Der = apply { header() }
        fun skip() { val (_, len) = header(); pos += len }
        fun read(expectedTag: Int): ByteArray {
            val (tag, len) = header()
            assertEquals(expectedTag, tag)
            return bytes.copyOfRange(pos, pos + len).also { pos += len }
        }
    }
}
