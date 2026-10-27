package com.tak.weartak_tracker.command

import kotlinx.coroutines.CancellationException

enum class NetworkCommandTransport(val label: String) {
    TAK_SERVERS("TAK Servers"),
    MULTICAST("multicast"),
}

data class NetworkCommandApplyResult(val applied: Boolean, val locked: Boolean = false)

fun interface NetworkCommandStore {
    suspend fun apply(transport: NetworkCommandTransport, enabled: Boolean): NetworkCommandApplyResult
}

class NetworkTransportCommandHandler(
    private val transport: NetworkCommandTransport,
    private val enabled: Boolean,
    private val networkLocked: suspend () -> Boolean,
    private val store: NetworkCommandStore,
    private val beforeDisableReply: suspend (ChatCommandRequest, String) -> Boolean,
    private val ackFailure: () -> Unit,
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        if (command.args.isNotEmpty()) {
            return ChatCommandResult(false, "Command rejected: ${command.name} takes no arguments")
        }
        if (networkLocked()) return lockedResult()

        val preAck = !enabled && request.isOnTransportRoute()
        if (preAck) {
            val written = try {
                beforeDisableReply(request, disableWarning())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (!written) {
                ackFailure()
                return ChatCommandResult(
                    false,
                    "${transport.label} not changed: unable to write the warning on the originating route",
                    replyAttempted = true,
                )
            }
        }

        val result = store.apply(transport, enabled)
        if (result.locked) return lockedResult()
        if (!result.applied) return ChatCommandResult(false, "${transport.label} update failed")
        return ChatCommandResult(true, "${transport.label} updated: enabled=$enabled", replyAttempted = preAck)
    }

    private fun lockedResult() =
        ChatCommandResult(false, "Network settings are locked; ${transport.label} not changed")

    private fun disableWarning(): String = when (transport) {
        NetworkCommandTransport.TAK_SERVERS ->
            "TAK Servers off requested; remote delivery unconfirmed. Use another route or UI to turn TAK back on."
        NetworkCommandTransport.MULTICAST ->
            "Multicast off requested; remote delivery unconfirmed. Use another route or UI to turn multicast back on."
    }

    private fun ChatCommandRequest.isOnTransportRoute(): Boolean = when (transport) {
        NetworkCommandTransport.TAK_SERVERS ->
            sourceServerId != "ditto:chat" && !sourceServerId.startsWith("multicast:")
        NetworkCommandTransport.MULTICAST -> sourceServerId.startsWith("multicast:")
    }
}
