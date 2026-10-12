package com.tak.weartak_tracker.command

data class ChatCommandRequest(
    val senderUid: String,
    val senderCallsign: String,
    val text: String,
    val messageId: String?,
    val sentAtMs: Long,
    val receivedAtMs: Long,
    val sourceServerId: String
)

data class ChatCommandResult(val ok: Boolean, val message: String)

fun interface ChatCommandHandler {
    suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult
}

/**
 * Runs `#weartaktrackercommand...` chat messages. Only direct messages from allow-listed callsigns are
 * acted on, and only when the Commands setting is on. Messages from other senders are swallowed
 * silently so the feature cannot be probed. Argument values are never logged or echoed.
 */
class ChatCommandProcessor(
    private val isEnabled: suspend () -> Boolean,
    private val allowedCallsigns: suspend () -> List<String>,
    private val handlers: Map<String, ChatCommandHandler>,
    private val reply: suspend (ChatCommandRequest, String) -> Unit,
    private val audit: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val authorizer: ChatCommandAuthorizer = ChatCommandAuthorizer()
) {
    /** Returns true when the message was a command and must not be shown as ordinary chat. */
    suspend fun handle(request: ChatCommandRequest): Boolean {
        val parsed = ChatCommandParser.parse(request.text)
        if (parsed is ChatCommandParse.NotACommand) return false
        if (!isEnabled()) return false

        val messageKey = request.messageId ?: "${request.sentAtMs}:${request.text.hashCode()}"
        val decision = authorizer.authorize(
            request.senderCallsign, allowedCallsigns(), messageKey, request.sentAtMs, now()
        )
        if (decision != ChatCommandDecision.ALLOWED) {
            audit("ignored command from ${request.senderCallsign}: $decision")
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
            ChatCommandResult(false, "Command failed")
        }
        audit("command ${command.name} from ${request.senderCallsign}: ${if (result.ok) "ok" else "failed"}")
        reply(request, result.message)
    }
}