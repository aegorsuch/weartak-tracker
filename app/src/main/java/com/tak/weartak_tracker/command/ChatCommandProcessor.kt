package com.tak.weartak_tracker.command

data class ChatCommandRequest(
    val senderUid: String,
    val senderCallsign: String,
    val text: String,
    val messageId: String?,
    val sentAtMs: Long,
    val receivedAtMs: Long,
    val sourceServerId: String,
    val replyRoomId: String? = null,
    val replyRoomName: String? = null,
    val replyMemberUids: List<String> = emptyList(),
) {
    override fun toString(): String = "ChatCommandRequest(source=$sourceServerId, text=redacted)"
}

data class ChatCommandResult(val ok: Boolean, val message: String, val replyAttempted: Boolean = false)

fun interface ChatCommandHandler {
    suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult
}

/**
 * Runs `#weartaktrackercommand...` chat messages. Only messages from allow-listed callsigns are
 * acted on, and only when the Commands setting is on. Direct, broadcast and member-group chats
 * use the same authorizer. Messages from other senders are swallowed
 * silently so the feature cannot be probed. Argument values are never logged or echoed.
 */
class ChatCommandProcessor(
    private val isEnabled: suspend () -> Boolean,
    private val allowedCallsigns: suspend () -> List<String>,
    private val handlers: Map<String, ChatCommandHandler>,
    private val reply: suspend (ChatCommandRequest, String) -> Unit,
    private val audit: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val authorizer: ChatCommandAuthorizer = ChatCommandAuthorizer(),
    private val positional: Map<String, List<String>> = emptyMap(),
    private val onAuthorized: (ChatCommandRequest) -> Unit = {},
    private val isRouteEnabled: suspend (ChatCommandRequest) -> Boolean = { true },
) {
    private val commandMutex = kotlinx.coroutines.sync.Mutex()

    /** Returns true when the message was a command and must not be shown as ordinary chat. */
    suspend fun handle(request: ChatCommandRequest): Boolean {
        commandMutex.lock()
        try {
            return handleSerialized(request)
        } finally {
            commandMutex.unlock()
        }
    }

    private suspend fun handleSerialized(request: ChatCommandRequest): Boolean {
        val parsed = ChatCommandParser.parse(request.text, positional)
        if (parsed is ChatCommandParse.NotACommand) return false
        if (!isEnabled()) {
            audit("ignored command from ${request.senderCallsign}: commands disabled")
            return false
        }
        if (!isRouteEnabled(request)) {
            audit("ignored command from disabled route")
            return true
        }

        val messageKey = request.senderUid + "|" + (request.messageId ?: (
            request.sentAtMs.toString() + ":" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(request.text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }))
        val decision = try {
            authorizer.authorize(request.senderCallsign, allowedCallsigns(), messageKey, request.sentAtMs, now())
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            audit("command replay journal unavailable")
            // No ack without a durable claim: writing an ack triggers another Ditto snapshot.
            return true
        }
        if (decision != ChatCommandDecision.ALLOWED) {
            audit("ignored command from ${request.senderCallsign}: $decision")
            // Allowed senders learn why; unknown senders get nothing so the feature can't be probed.
            if (decision == ChatCommandDecision.STALE) {
                reply(request, "Command rejected: message time is missing or more than 5 minutes off. Check both devices' clocks.")
            }
            return true
        }

        when (parsed) {
            is ChatCommandParse.Malformed -> {
                audit("malformed command from ${request.senderCallsign}: ${parsed.reason}")
                reply(request, "Command rejected: ${parsed.reason}")
            }
            is ChatCommandParse.Parsed -> {
                try {
                    onAuthorized(request)
                } catch (_: Exception) {
                    audit("command room roster unavailable")
                    reply(request, "Command rejected: room roster unavailable")
                    return true
                }
                run(parsed.command, request)
            }
            ChatCommandParse.NotACommand -> Unit
        }
        return true
    }

    private suspend fun run(command: ChatCommand, request: ChatCommandRequest) {
        val handler = handlers[command.name]
        if (handler == null) {
            audit("unknown command ${command.name} from ${request.senderCallsign}")
            reply(request, "Unknown command: ${command.name}")
            return
        }
        val result = try {
            handler.execute(command, request)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            ChatCommandResult(false,
                if (command.name in setOf("setpdl", "toggleditto")) "Ditto update failed" else "Command failed")
        }
        audit("command ${command.name} from ${request.senderCallsign}: ${if (result.ok) "ok" else "failed"}")
        if (!result.replyAttempted) reply(request, result.message)
    }
}