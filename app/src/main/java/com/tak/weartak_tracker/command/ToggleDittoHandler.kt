package com.tak.weartak_tracker.command

import kotlinx.coroutines.CancellationException

fun interface DittoToggleStore {
    suspend fun apply(enabled: Boolean): DittoPdlApplyResult
}

class ToggleDittoHandler(
    private val networkLocked: suspend () -> Boolean,
    private val store: DittoToggleStore,
    private val beforeDisableReply: suspend (ChatCommandRequest, String) -> Unit,
    private val ackFailure: () -> Unit,
) : ChatCommandHandler {
    override suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult {
        if (command.args.keys != setOf("state") || command.args["state"] !in setOf("on", "off")) {
            return ChatCommandResult(false, "Command rejected: toggleditto needs exactly on or off")
        }
        if (networkLocked()) return ChatCommandResult(false, "Ditto not changed: settings locked")
        val enabled = command.args["state"] == "on"
        val preAck = !enabled && request.sourceServerId == "ditto:chat"
        if (preAck) {
            try {
                beforeDisableReply(request, "Ditto off requested; remote delivery unconfirmed. Use another route or UI to turn on.")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ackFailure()
            }
        }
        val result = store.apply(enabled)
        if (result.locked) return ChatCommandResult(false, "Ditto not changed: settings locked")
        if (!result.applied) return ChatCommandResult(false, "Ditto update failed")
        return ChatCommandResult(true, "Ditto updated: enabled=$enabled", replyAttempted = preAck)
    }
}
