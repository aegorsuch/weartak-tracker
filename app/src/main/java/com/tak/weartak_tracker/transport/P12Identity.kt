package com.tak.weartak_tracker.transport

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import java.security.cert.CertPathValidator
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.util.Date
import javax.net.ssl.KeyManagerFactory

/** Deliberately not a data class: never render private material in logs or error replies. */
class P12Identity private constructor(
    val bytes: ByteArray,
    val credentials: TakCertificates.Credentials,
    val fingerprint: String,
    val expiresAt: Long,
) : AutoCloseable {
    override fun close() {
        bytes.fill(0)
        credentials.password.fill('\u0000')
    }

    override fun toString(): String = "P12Identity(redacted)"

    companion object {
        fun parseHex(hex: String, password: CharArray, now: Long = System.currentTimeMillis()): P12Identity {
            require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.length <= 32_768)
            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            val bytes = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
            try {
                return validate(bytes, password, now)
            } catch (error: Exception) {
                bytes.fill(0)
                throw error
            }
        }

        fun validate(bytes: ByteArray, password: CharArray, now: Long = System.currentTimeMillis()): P12Identity {
            require(bytes.size in 1..16_384)
            require(bytes.size >= 2 && bytes[0] == 0x30.toByte())
            val firstLength = bytes[1].toInt() and 0xff
            val lengthBytes = if (firstLength < 128) 0 else firstLength and 0x7f
            require(lengthBytes in 0..4 && bytes.size >= 2 + lengthBytes)
            val length = if (lengthBytes == 0) firstLength.toLong() else {
                require(bytes[2] != 0.toByte())
                (0 until lengthBytes).fold(0L) { value, i -> (value shl 8) or (bytes[2 + i].toLong() and 0xff) }
            }
            require(length == (bytes.size - 2 - lengthBytes).toLong())
            val store = KeyStore.getInstance("PKCS12")
            store.load(ByteArrayInputStream(bytes), password)
            val keys = store.aliases().toList().filter(store::isKeyEntry)
            require(keys.size == 1)
            val key = store.getKey(keys.single(), password) as? PrivateKey
                ?: throw IllegalArgumentException("No private key")
            val chain = store.getCertificateChain(keys.single())?.map {
                it as? X509Certificate ?: throw IllegalArgumentException("Non-X509 certificate")
            }.orEmpty()
            require(chain.isNotEmpty())
            chain.forEach { it.checkValidity(Date(now)) }
            val leaf = chain.first()
            require(leaf.basicConstraints < 0)
            require(leaf.keyUsage?.let { it.isNotEmpty() && it[0] } != false)
            require(leaf.extendedKeyUsage?.any { it == "1.3.6.1.5.5.7.3.2" || it == "2.5.29.37.0" } != false)
            chain.zipWithNext().forEach { (child, parent) ->
                require(child.issuerX500Principal == parent.subjectX500Principal && parent.basicConstraints >= 0)
                require(parent.keyUsage?.let { it.size > 5 && it[5] } != false)
                child.verify(parent.publicKey)
            }
            val anchors = TakCertificates.selectTrustAnchors(store)
            require(anchors.isNotEmpty())
            val path = CertificateFactory.getInstance("X.509").generateCertPath(
                chain.takeWhile { cert -> cert !in anchors })
            CertPathValidator.getInstance("PKIX").validate(path, PKIXParameters(
                anchors.map { TrustAnchor(it, null) }.toSet()).apply {
                date = Date(now)
                isRevocationEnabled = false
            })
            val algorithm = when (key.algorithm) {
                "RSA" -> "SHA256withRSA"
                "EC" -> "SHA256withECDSA"
                "DSA" -> "SHA256withDSA"
                else -> throw IllegalArgumentException("Unsupported private key")
            }
            val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val signature = Signature.getInstance(algorithm).run {
                initSign(key); update(challenge); sign()
            }
            require(Signature.getInstance(algorithm).run {
                initVerify(leaf.publicKey); update(challenge); verify(signature)
            })
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).init(store, password)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            return P12Identity(bytes, TakCertificates.Credentials(store, password.copyOf(), "chat import"),
                fingerprint, leaf.notAfter.time)
        }
    }
}
