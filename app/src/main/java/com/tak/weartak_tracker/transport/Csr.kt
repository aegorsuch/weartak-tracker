package com.tak.weartak_tracker.transport

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.Signature

/**
 * Minimal PKCS#10 CSR encoder (SHA256withRSA) so the app does not need BouncyCastle.
 * Subject RDNs are encoded in the order given, matching "CN=user, O=..., OU=..." used by WearTAK-CIV.
 */
object Csr {
    private val OIDS = mapOf(
        "CN" to "2.5.4.3", "C" to "2.5.4.6", "L" to "2.5.4.7", "ST" to "2.5.4.8",
        "O" to "2.5.4.10", "OU" to "2.5.4.11", "STREET" to "2.5.4.9", "SERIALNUMBER" to "2.5.4.5",
        "DC" to "0.9.2342.19200300.100.1.25", "UID" to "0.9.2342.19200300.100.1.1",
        "E" to "1.2.840.113549.1.9.1", "EMAILADDRESS" to "1.2.840.113549.1.9.1",
    )
    private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"

    fun build(subject: List<Pair<String, String>>, keyPair: KeyPair): ByteArray {
        val info = seq(
            integer0(),
            name(subject),
            keyPair.public.encoded,
            byteArrayOf(0xA0.toByte(), 0x00),
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(info)
            sign()
        }
        return seq(info, seq(oid(SHA256_WITH_RSA), byteArrayOf(0x05, 0x00)), tlv(0x03, byteArrayOf(0) + sig))
    }

    internal fun name(subject: List<Pair<String, String>>): ByteArray = seq(
        *subject.map { (key, value) ->
            val upper = key.trim().uppercase()
            val oid = OIDS[upper] ?: if (upper.matches(Regex("[0-9.]+"))) upper
            else throw IllegalArgumentException("Unsupported DN attribute $key")
            val tag = when (upper) {
                "C" -> 0x13
                "E", "EMAILADDRESS", "DC" -> 0x16
                else -> 0x0C
            }
            tlv(0x31, seq(oid(oid), tlv(tag, value.toByteArray(Charsets.UTF_8))))
        }.toTypedArray(),
    )

    private fun integer0() = byteArrayOf(0x02, 0x01, 0x00)

    internal fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        parts.drop(2).forEach { v ->
            val stack = ArrayList<Int>()
            var n = v
            stack.add((n and 0x7F).toInt())
            n = n shr 7
            while (n > 0) {
                stack.add(((n and 0x7F) or 0x80).toInt())
                n = n shr 7
            }
            stack.asReversed().forEach(out::write)
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun seq(vararg parts: ByteArray): ByteArray =
        tlv(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    internal fun tlv(tag: Int, value: ByteArray): ByteArray {
        val len = value.size
        val lenBytes = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
            else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        }
        return byteArrayOf(tag.toByte()) + lenBytes + value
    }
}
