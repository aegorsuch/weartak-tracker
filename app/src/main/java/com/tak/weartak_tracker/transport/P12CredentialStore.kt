package com.tak.weartak_tracker.transport

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.tak.weartak_tracker.data.SecretBox
import com.tak.weartak_tracker.data.TakServerConfig
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** A private encrypted override per configured server ID AND endpoint, ahead of host-wide sideloads. */
internal class P12CredentialStore(private val context: Context) {
    private fun file(server: TakServerConfig): AtomicFile {
        val key = server.id + "\u0000" + server.endpointKey
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.filesDir, "p12_$digest.enc"))
    }

    fun load(server: TakServerConfig): TakCertificates.Credentials? {
        val target = file(server)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        val decrypted = SecretBox.decrypt(String(target.readFully(), Charsets.UTF_8))
            ?: throw IllegalStateException("Cannot decrypt imported client identity")
        val json = JSONObject(decrypted)
        val password = json.getString("password").toCharArray()
        val bytes = Base64.decode(json.getString("p12"), Base64.NO_WRAP)
        try {
            return P12Identity.validate(bytes, password).credentials
        } finally {
            password.fill('\u0000')
            bytes.fill(0)
        }
    }

    fun stage(server: TakServerConfig, identity: P12Identity): Staged {
        val target = file(server)
        val previous = if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) target.readFully() else null
        val encrypted = SecretBox.encrypt(JSONObject()
            .put("p12", Base64.encodeToString(identity.bytes, Base64.NO_WRAP))
            .put("password", String(identity.credentials.password)).toString()).toByteArray(Charsets.UTF_8)
        return Staged(target, previous, encrypted)
    }

    class Staged(private val target: AtomicFile, private val previous: ByteArray?, private val next: ByteArray) {
        fun commit() = write(next)
        fun rollback() {
            if (previous == null) {
                target.delete()
                check(!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) {
                    "Cannot remove staged client identity"
                }
            } else write(previous)
        }
        private fun write(bytes: ByteArray) {
            val stream = target.startWrite()
            try {
                stream.write(bytes)
                target.finishWrite(stream)
                check(target.readFully().contentEquals(bytes)) { "Client identity commit verification failed" }
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
        }
    }
}
