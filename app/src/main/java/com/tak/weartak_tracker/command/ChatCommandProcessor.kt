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
)

data class ChatCommandResult(val ok: Boolean, val message: String)

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
    private val positional: Map<String, List<String>> = emptyMap()
) {
    /** Returns true when the message was a command and must not be shown as ordinary chat. */
    suspend fun handle(request: ChatCommandRequest): Boolean {
        val parsed = ChatCommandParser.parse(request.text, positional)
        if (parsed is ChatCommandParse.NotACommand) return false
        if (!isEnabled()) {
            audit("ignored command from ${request.senderCallsign}: commands disabled")
            return false
        }

        val messageKey = request.messageId ?: "${request.sentAtMs}:${request.text.hashCode()}"
        val decision = authorizer.authorize(
            request.senderCallsign, allowedCallsigns(), messageKey, request.sentAtMs, now()
        )
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
            is ChatCommandParse.Parsed -> run(parsed.command, request)
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
            ChatCommandResult(false, if (command.name == "setpdl") "Ditto update failed" else "Command failed")
        }
        audit("command ${command.name} from ${request.senderCallsign}: ${if (result.ok) "ok" else "failed"}")
        reply(request, result.message)
    }
}