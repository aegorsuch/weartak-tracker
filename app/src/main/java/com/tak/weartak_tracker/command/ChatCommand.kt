package com.tak.weartak_tracker.command

/** A remote command sent as a chat message: `#weartaktrackercommand<name> key=value key2="quoted value"`. */
data class ChatCommand(val name: String, val args: Map<String, String>) {
    override fun toString(): String = "ChatCommand(name=$name, args=redacted)"
}

sealed interface ChatCommandParse {
    object NotACommand : ChatCommandParse
    data class Malformed(val reason: String) : ChatCommandParse
    data class Parsed(val command: ChatCommand) : ChatCommandParse
}

object ChatCommandParser {
    const val PREFIX = "#weartaktrackercommand"
    const val MAX_LENGTH = 768
    const val MAX_ARGS = 12
    const val MAX_VALUE_LENGTH = 128
    const val COMMON_PREFIX = "#weartakcommand"
    const val MAX_PDL_LENGTH = 16_384
    const val MAX_PDL_COMMAND_LENGTH = 17_408
    const val MAX_P12_HEX_LENGTH = 32_768
    const val MAX_P12_COMMAND_LENGTH = 34_816
    private val NAME = Regex("[a-z]{1,32}")
    private val KEY = Regex("[a-z][a-z0-9]{0,15}")

    private val KEY_VALUE_START = Regex("^[A-Za-z][A-Za-z0-9]{0,15}=")

    fun hasCommandPrefix(text: String): Boolean = text.startsWith(PREFIX, ignoreCase = true) ||
        text.startsWith(COMMON_PREFIX, ignoreCase = true)

    /**
     * Anything not starting with the prefix is ordinary chat and is never inspected further.
     *
     * Commands listed in [positional] also accept values without keys: one key takes the whole rest of
     * the message (`setusertype Coalition Civilian`), several keys take `;`-separated values in order.
     * `enrollserver` also accepts comma-separated values, with the password taking the remaining text.
     */
    fun parse(text: String, positional: Map<String, List<String>> = emptyMap()): ChatCommandParse {
        val body = text.trim()
        if (!hasCommandPrefix(body)) return ChatCommandParse.NotACommand
        val prefixLength = if (body.startsWith(PREFIX, true)) PREFIX.length else COMMON_PREFIX.length
        val rest = body.substring(prefixLength).trimStart()
        val nameEnd = rest.indexOfFirst { it.isWhitespace() }.let { if (it < 0) rest.length else it }
        val name = rest.substring(0, nameEnd).lowercase()
        if (name != "usep12" && !NAME.matches(name)) return ChatCommandParse.Malformed("invalid command name")
        val maxLength = when (name) {
            "setpdl" -> MAX_PDL_COMMAND_LENGTH
            "usep12" -> MAX_P12_COMMAND_LENGTH
            else -> MAX_LENGTH
        }
        if (body.length > maxLength) return ChatCommandParse.Malformed("too long")
        val argText = rest.substring(nameEnd).trim()
        if (name == "toggleditto") {
            if (!argText.equals("on", true) && !argText.equals("off", true)) {
                return ChatCommandParse.Malformed("toggleditto needs exactly on or off")
            }
            return ChatCommandParse.Parsed(ChatCommand(name, mapOf("state" to argText.lowercase())))
        }
        if (name == "usep12") {
            val hex = argText.takeWhile { !it.isWhitespace() }
            if (hex.isEmpty() || hex.length > MAX_P12_HEX_LENGTH || hex.length % 2 != 0 ||
                hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }
            ) return ChatCommandParse.Malformed("usep12 needs bounded hexadecimal PKCS12 data")
            val options = parse("${PREFIX}certoptions ${argText.substring(hex.length).trim()}")
            if (options !is ChatCommandParse.Parsed ||
                options.command.args.keys.any { it != "password" && it != "server" }
            ) return ChatCommandParse.Malformed("usep12 accepts only password and server options")
            return ChatCommandParse.Parsed(ChatCommand(name, options.command.args + ("hex" to hex)))
        }
        if (name == "setpdl") {
            if (argText.isEmpty() || argText.any(Char::isWhitespace)) {
                return ChatCommandParse.Malformed("setpdl needs community or one encoded PDL")
            }
            if (argText.length > MAX_PDL_LENGTH || argText.any(Char::isISOControl)) {
                return ChatCommandParse.Malformed("invalid argument value")
            }
            return ChatCommandParse.Parsed(ChatCommand(name, mapOf("pdl" to argText)))
        }
        val keys = positional[name]
        if (keys != null && argText.isNotEmpty() && !KEY_VALUE_START.containsMatchIn(argText)) {
            if (name == "enrollserver") {
                return parseDelimitedEnrollServer(argText, keys)
            }
            return parsePositional(name, argText, keys)
        }
        val args = LinkedHashMap<String, String>()
        var i = nameEnd
        while (true) {
            while (i < rest.length && rest[i].isWhitespace()) i++
            if (i >= rest.length) break
            val eq = rest.indexOf('=', i)
            if (eq < 0) return ChatCommandParse.Malformed("argument without value")
            val key = rest.substring(i, eq).lowercase()
            if (!KEY.matches(key)) return ChatCommandParse.Malformed("invalid argument name")
            if (key in args) return ChatCommandParse.Malformed("duplicate argument")
            if (args.size >= MAX_ARGS) return ChatCommandParse.Malformed("too many arguments")
            i = eq + 1
            val value = StringBuilder()
            if (i < rest.length && rest[i] == '"') {
                val quoted = readQuotedValue(rest, i) ?: return ChatCommandParse.Malformed("unterminated quote")
                value.append(quoted.first)
                i = quoted.second
                if (i < rest.length && !rest[i].isWhitespace()) return ChatCommandParse.Malformed("text after quote")
            } else {
                while (i < rest.length && !rest[i].isWhitespace()) value.append(rest[i++])
            }
            if (value.isEmpty() || value.length > MAX_VALUE_LENGTH || value.any { it.isISOControl() }) {
                return ChatCommandParse.Malformed("invalid argument value")
            }
            args[key] = value.toString()
        }
        return ChatCommandParse.Parsed(ChatCommand(name, args))
    }

    private fun parsePositional(name: String, argText: String, keys: List<String>): ChatCommandParse {
        val values = if (keys.size == 1) {
            val quoted = argText.length >= 2 && argText.startsWith('"') && argText.endsWith('"') &&
                argText.count { it == '"' } == 2
            listOf(if (quoted) argText.substring(1, argText.length - 1).trim() else argText)
        } else {
            argText.split(';', limit = keys.size).map { it.trim() }
        }
        if (values.size != keys.size) return ChatCommandParse.Malformed("expected ${keys.size} values separated by ;")
        if (values.any { it.isEmpty() || it.length > MAX_VALUE_LENGTH || it.any(Char::isISOControl) }) {
            return ChatCommandParse.Malformed("invalid argument value")
        }
        return ChatCommandParse.Parsed(ChatCommand(name, LinkedHashMap(keys.zip(values).toMap())))
    }

    private fun readQuotedValue(text: String, start: Int): Pair<String, Int>? {
        val value = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            val c = text[i++]
            if (c == '\\' && i < text.length && (text[i] == '"' || text[i] == '\\')) value.append(text[i++])
            else if (c == '"') return value.toString() to i
            else value.append(c)
        }
        return null
    }

    private fun parseDelimitedEnrollServer(argText: String, keys: List<String>): ChatCommandParse {
        val firstEnd = if (argText.startsWith('"')) {
            readQuotedValue(argText, 0)?.second ?: return ChatCommandParse.Malformed("unterminated quote")
        } else 0
        val delimiter = argText.substring(firstEnd).firstOrNull { it == ',' || it == ';' } ?: ';'
        val args = LinkedHashMap<String, String>()
        var i = 0
        for ((index, key) in keys.withIndex()) {
            while (i < argText.length && argText[i].isWhitespace()) i++
            val value = if (i < argText.length && argText[i] == '"') {
                val quoted = readQuotedValue(argText, i)
                    ?: return ChatCommandParse.Malformed("unterminated quote")
                i = quoted.second
                while (i < argText.length && argText[i].isWhitespace()) i++
                if (i < argText.length && (index == keys.lastIndex || argText[i] != delimiter)) {
                    return ChatCommandParse.Malformed("text after quote")
                }
                quoted.first
            } else {
                val end = if (index == keys.lastIndex) argText.length else
                    argText.indexOf(delimiter, i).let { if (it < 0) argText.length else it }
                argText.substring(i, end).trim().also { i = end }
            }
            if (value.isEmpty() || value.length > MAX_VALUE_LENGTH || value.any(Char::isISOControl)) {
                return ChatCommandParse.Malformed("invalid argument value")
            }
            args[key] = value
            if (index != keys.lastIndex) {
                if (i >= argText.length) {
                    return ChatCommandParse.Malformed("expected ${keys.size} values separated by $delimiter")
                }
                i++
            }
        }
        return ChatCommandParse.Parsed(ChatCommand("enrollserver", args))
    }
}