package com.tak.weartak_tracker

import com.tak.weartak_tracker.command.*
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.transport.P12Identity
import com.tak.weartak_tracker.transport.commitVerifiedIdentity
import com.tak.weartak_tracker.transport.TakCertificates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.net.Socket
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class UseP12Test {
    private val server = TakServerConfig(id = "ops", address = "tak.example.test")
    private val request = ChatCommandRequest("admin-device", "ADMIN", "", "id", 1, 1, "server")

    @Test
    fun parserAcceptsTypicalAndMaximumHexWithoutRaisingOtherCommandLimits() {
        for (size in listOf(5_120, 8_192, ChatCommandParser.MAX_P12_HEX_LENGTH)) {
            val result = ChatCommandParser.parse("#weartakcommandusep12 " + "ab".repeat(size / 2) +
                " password=\"synthetic password\" server=ops") as ChatCommandParse.Parsed
            assertEquals(size, result.command.args.getValue("hex").length)
            assertEquals("synthetic password", result.command.args["password"])
            assertEquals("ops", result.command.args["server"])
        }
        assertTrue(ChatCommandParser.parse("#weartakcommandsetcallsign value=" + "a".repeat(129)) is ChatCommandParse.Malformed)
        for (args in listOf("", "abc", "xx", "ab enabled=true", "ab password=x password=y", "ab server=\"unfinished",
            "ab ".repeat(2), "ab".repeat(16_385))) {
            val result = ChatCommandParser.parse("#weartakcommandusep12 $args")
            assertTrue(result is ChatCommandParse.Malformed)
            assertFalse(result.toString().contains("synthetic password"))
        }
    }

    @Test
    fun generatedClientP12ValidatesAndSecretsAreRedactedAndWiped() {
        val identity = P12Identity.parseHex(hex, testPassword.toCharArray())
        assertEquals(64, identity.fingerprint.length)
        assertTrue(identity.expiresAt > System.currentTimeMillis())
        assertFalse(identity.toString().contains(hex))
        val bytes = identity.bytes
        val password = identity.credentials.password
        identity.close()
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(password.all { it == '\u0000' })
        assertFalse(ChatCommand("usep12", mapOf("hex" to hex)).toString().contains(hex))
        assertFalse(request.copy(text = hex).toString().contains(hex))
    }

    @Test
    fun wrongPasswordExpiredCertCertificateOnlyAndMultipleKeysAreRejected() {
        assertTrue(runCatching { P12Identity.parseHex(hex, "wrong".toCharArray()) }.isFailure)
        assertTrue(runCatching { P12Identity.parseHex(hex + "00", testPassword.toCharArray()) }.isFailure)
        assertTrue(runCatching {
            P12Identity.parseHex(hex, testPassword.toCharArray(), Long.MAX_VALUE)
        }.isFailure)
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(bytes), testPassword.toCharArray())
        }
        val certificateOnly = KeyStore.getInstance("PKCS12").apply {
            load(null, null); setCertificateEntry("leaf", ks.getCertificate("client"))
        }
        assertTrue(runCatching {
            P12Identity.validate(encode(certificateOnly), testPassword.toCharArray())
        }.isFailure)
        ks.setKeyEntry("second", ks.getKey("client", testPassword.toCharArray()),
            testPassword.toCharArray(), ks.getCertificateChain("client"))
        assertTrue(runCatching { P12Identity.validate(encode(ks), testPassword.toCharArray()) }.isFailure)
    }

    @Test
    fun handlerRequiresExplicitExistingServerAndNeverEchoesInput() = runBlocking {
        val installed = mutableListOf<String>()
        fun handler(list: List<TakServerConfig>, locked: Boolean = false, applies: Boolean = true) =
            UseP12Handler({ list }, { locked }, P12Installer { target, _ ->
                installed += target.id; applies
            })
        val args = mapOf("hex" to hex, "password" to testPassword, "server" to "ops")
        val command = ChatCommand("usep12", args)
        assertTrue(handler(listOf(server)).execute(command, request).ok)
        val missing = handler(listOf(server)).execute(command.copy(args = args - "server"), request)
        assertFalse(missing.ok)
        assertEquals("P12 not changed: server=<configured-id> is required", missing.message)
        val unknown = handler(listOf(server)).execute(command.copy(args = args + ("server" to "missing")), request)
        assertFalse(unknown.ok)
        assertEquals("P12 not changed: unknown server ID", unknown.message)
        assertTrue(handler(listOf(server, server.copy(id = "other"))).execute(
            command.copy(args = args + ("server" to "other")), request).ok)
        assertEquals(listOf("ops", "other"), installed)
        assertFalse(handler(listOf(server), locked = true).execute(command, request).ok)
        assertFalse(handler(listOf(server.copy(enabled = false))).execute(command, request).ok)
        val failed = handler(listOf(server), applies = false).execute(command, request)
        assertFalse(failed.ok)
        assertFalse(failed.message.contains(hex))
        assertFalse(failed.message.contains(testPassword))
        assertFalse(handler(listOf(server)).execute(command.copy(args = args + ("password" to "bad")), request).ok)
    }

    @Test
    fun defaultPasswordCanBeUsedWithoutAnExplicitPasswordArgument() = runBlocking {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(bytes), testPassword.toCharArray())
        }
        val key = ks.getKey("client", testPassword.toCharArray())
        val defaultPassword = TakCertificates.STORE_PASSWORD.toCharArray()
        ks.setKeyEntry("client", key, defaultPassword, ks.getCertificateChain("client"))
        val encoded = ByteArrayOutputStream().also { ks.store(it, defaultPassword) }.toByteArray()
        var applied = false
        val handler = UseP12Handler({ listOf(server) }, { false }, P12Installer { _, _ -> applied = true; true })
        val result = handler.execute(ChatCommand("usep12", mapOf(
            "hex" to encoded.joinToString("") { "%02x".format(it) }, "server" to server.id)), request)
        assertTrue(result.ok)
        assertTrue(applied)
        assertFalse(result.message.contains(TakCertificates.STORE_PASSWORD))
    }

    @Test
    fun validatedCandidateAuthenticatesWithRealMutualTlsAndRejectsWrongServerName() {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(fixture.second), testPassword.toCharArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, testPassword.toCharArray())
        }
        val trust = KeyStore.getInstance("PKCS12").apply {
            load(null, null); setCertificateEntry("root", store.getCertificate("root"))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
        P12Identity.parseHex(hex, testPassword.toCharArray()).use { identity ->
            for (correctName in listOf(true, false)) {
                val listener = context.serverSocketFactory.createServerSocket(0, 1,
                    java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
                listener.soTimeout = 5_000
                var authenticated = false
                val worker = thread(isDaemon = true) {
                    try {
                        (listener.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 5_000
                            socket.enabledProtocols = arrayOf("TLSv1.2")
                            socket.needClientAuth = true
                            socket.startHandshake()
                            authenticated = socket.session.peerCertificates.isNotEmpty()
                            socket.outputStream.write(1)
                        }
                    } catch (_: Exception) {
                        // The wrong-name case must abort the server's handshake.
                    }
                }
                try {
                    val result = runCatching {
                        val plain = Socket("127.0.0.1", listener.localPort)
                        try {
                            val factory = TakCertificates.socketFactory(identity.credentials, "127.0.0.1",
                                if (correctName) "localhost" else "wrong.example.test")
                            (factory.createSocket(plain, "localhost", listener.localPort, true) as SSLSocket).use { socket ->
                                socket.soTimeout = 5_000
                                socket.startHandshake()
                                assertEquals(1, socket.inputStream.read())
                            }
                        } finally {
                            plain.close()
                        }
                    }
                    assertEquals(correctName, result.isSuccess)
                    worker.join(6_000)
                    assertFalse(worker.isAlive)
                    assertEquals(correctName, authenticated)
                } finally {
                    listener.close()
                }
            }
        }
    }

    @Test
    fun verifiedCommitActivatesOnlyAfterCommitAndRollsBackFailuresAndChangedTarget() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(commitVerifiedIdentity({ true }, { events += "commit"; true },
            { events += "activate" }, { events += "rollback" }))
        assertEquals(listOf("commit", "activate"), events)
        for (mode in listOf("locked", "changed", "disk", "activate", "cancel")) {
            events.clear()
            var current = true
            runCatching {
                commitVerifiedIdentity({ current }, {
                    events += "commit"
                    if (mode == "cancel") throw kotlinx.coroutines.CancellationException("synthetic cancellation")
                    if (mode == "disk") throw java.io.IOException("synthetic failure")
                    if (mode == "changed") current = false
                    mode != "locked"
                }, {
                    if (mode == "activate") throw IllegalStateException("synthetic failure")
                    events += "activate"
                }, { events += "rollback" })
            }
            assertEquals(listOf("commit", "rollback"), events)
        }
        events.clear()
        assertFalse(commitVerifiedIdentity({ false }, { events += "commit"; true },
            { events += "activate" }, { events += "rollback" }))
        assertTrue(events.isEmpty())
    }

    private fun encode(store: KeyStore): ByteArray = ByteArrayOutputStream().also {
        store.store(it, testPassword.toCharArray())
    }.toByteArray()

    companion object {
        // Generated solely for tests at runtime. No production certificates or private keys are fixtures.
        private const val testPassword = "synthetic-test-password"
        private val fixture: Pair<ByteArray, ByteArray> by lazy {
            val dir = Files.createTempDirectory("tracker-p12-test-").toFile()
            val keytool = File(System.getProperty("java.home"), "bin" + File.separator +
                if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
            fun run(vararg args: String) {
                val process = ProcessBuilder(listOf(keytool.path) + args).directory(dir).redirectErrorStream(true).start()
                process.inputStream.readBytes()
                check(process.waitFor() == 0) { "Synthetic PKCS12 fixture generation failed" }
            }
            try {
                run("-genkeypair", "-alias", "root", "-keystore", "root.p12", "-storetype", "PKCS12",
                    "-storepass", testPassword, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Test Root",
                    "-validity", "3650", "-ext", "bc=ca:true", "-ext", "ku=keyCertSign,cRLSign")
                run("-exportcert", "-alias", "root", "-keystore", "root.p12", "-storepass", testPassword, "-file", "root.cer")
                run("-genkeypair", "-alias", "client", "-keystore", "client.p12", "-storetype", "PKCS12",
                    "-storepass", testPassword, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Synthetic Client")
                run("-certreq", "-alias", "client", "-keystore", "client.p12", "-storepass", testPassword, "-file", "client.csr")
                run("-gencert", "-alias", "root", "-keystore", "root.p12", "-storepass", testPassword,
                    "-infile", "client.csr", "-outfile", "client.cer", "-validity", "365",
                    "-ext", "eku=clientAuth", "-ext", "ku=digitalSignature")
                run("-importcert", "-noprompt", "-alias", "root", "-keystore", "client.p12",
                    "-storepass", testPassword, "-file", "root.cer")
                run("-importcert", "-noprompt", "-alias", "client", "-keystore", "client.p12",
                    "-storepass", testPassword, "-file", "client.cer")
                run("-genkeypair", "-alias", "server", "-keystore", "server.p12", "-storetype", "PKCS12",
                    "-storepass", testPassword, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost")
                run("-certreq", "-alias", "server", "-keystore", "server.p12", "-storepass", testPassword, "-file", "server.csr")
                run("-gencert", "-alias", "root", "-keystore", "root.p12", "-storepass", testPassword,
                    "-infile", "server.csr", "-outfile", "server.cer", "-validity", "365",
                    "-ext", "eku=serverAuth", "-ext", "ku=digitalSignature,keyEncipherment", "-ext", "san=dns:localhost")
                run("-importcert", "-noprompt", "-alias", "root", "-keystore", "server.p12",
                    "-storepass", testPassword, "-file", "root.cer")
                run("-importcert", "-noprompt", "-alias", "server", "-keystore", "server.p12",
                    "-storepass", testPassword, "-file", "server.cer")
                File(dir, "client.p12").readBytes() to File(dir, "server.p12").readBytes()
            } finally {
                dir.listFiles()?.forEach { check(it.delete()) }
                check(dir.delete())
            }
        }
        private val bytes: ByteArray get() = fixture.first
        private val hex: String by lazy { bytes.joinToString("") { "%02x".format(it) } }
    }
}
