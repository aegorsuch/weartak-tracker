package com.tak.weartak_tracker.command

import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.transport.P12Identity
import com.tak.weartak_tracker.transport.TakCertificates
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface P12Installer {
    suspend fun install(server: TakServerConfig, identity: P12Identity): Boolean
}

class UseP12Handler(
    private val servers: suspend () -> List<TakServerConfig>,
    private val networkLocked: suspend () -> Boolean,
    private val installer: P12Installer,
    private val now: () -> Long = System::currentTimeMillis,
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        if (networkLocked()) return ChatCommandResult(false, "P12 not changed: settings locked")
        if (command.args.keys.any { it !in setOf("hex", "password", "server") } || "hex" !in command.args) {
            return ChatCommandResult(false, "P12 not changed: invalid arguments")
        }
        val configured = servers()
        val targetId = command.args["server"]?.takeIf { it.isNotBlank() }
            ?: return ChatCommandResult(false, "P12 not changed: server=<configured-id> is required")
        val target = configured.singleOrNull { it.id == targetId }
            ?: return ChatCommandResult(false, "P12 not changed: unknown server ID")
        if (!target.enabled || target.address.isBlank()) return ChatCommandResult(false, "P12 not changed: server is disabled or unconfigured")
        val password = (command.args["password"] ?: TakCertificates.STORE_PASSWORD).toCharArray()
        val identity = try {
            withContext(Dispatchers.IO) { P12Identity.parseHex(command.args.getValue("hex"), password, now()) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ChatCommandResult(false, "P12 not changed: invalid identity or password")
        } finally {
            password.fill('\u0000')
        }
        return identity.use {
            try {
                if (!installer.install(target, it)) return@use ChatCommandResult(false, "P12 not changed: apply failed")
                ChatCommandResult(true, "P12 updated: server=${target.id} sha256=${it.fingerprint} " +
                    "expiry=${Instant.ofEpochMilli(it.expiresAt)}")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ChatCommandResult(false, "P12 not changed: apply failed")
            }
        }
    }
}
