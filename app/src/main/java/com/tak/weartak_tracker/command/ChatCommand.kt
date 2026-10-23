package com.tak.weartak_tracker.command

/** A remote command sent as a chat message: `#weartaktrackercommand<name> key=value key2="quoted value"`. */
data class ChatCommand(val name: String, val args: Map<String, String>)

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
    private val NAME = Regex("[a-z]{1,32}")
    private val KEY = Regex("[a-z][a-z0-9]{0,15}")

    private val KEY_VALUE_START = Regex("^[A-Za-z][A-Za-z0-9]{0,15}=")

    /**
     * Anything not starting with the prefix is ordinary chat and is never inspected further.
     *
     * Commands listed in [positional] also accept values without keys: one key takes the whole rest of
     * the message (`setusertype Coalition Civilian`), several keys take `;`-separated values in order
     * (`enrollserver OPS; tak.example.org; 8089; user; pass`, where the last value may contain `;`).
     */
    fun parse(text: String, positional: Map<String, List<String>> = emptyMap()): ChatCommandParse {
        val body = text.trim()
        if (!body.startsWith(PREFIX, ignoreCase = true)) return ChatCommandParse.NotACommand
        if (body.length > MAX_LENGTH) return ChatCommandParse.Malformed("too long")
        val rest = body.substring(PREFIX.length)
        val nameEnd = rest.indexOfFirst { it.isWhitespace() }.let { if (it < 0) rest.length else it }
        val name = rest.substring(0, nameEnd).lowercase()
        if (!NAME.matches(name)) return ChatCommandParse.Malformed("invalid command name")
        val argText = rest.substring(nameEnd).trim()
        val keys = positional[name]
        if (keys != null && argText.isNotEmpty() && !KEY_VALUE_START.containsMatchIn(argText)) {
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
                i++
                var closed = false
                while (i < rest.length) {
                    val c = rest[i++]
                    if (c == '\\' && i < rest.length && (rest[i] == '"' || rest[i] == '\\')) value.append(rest[i++])
                    else if (c == '"') { closed = true; break }
                    else value.append(c)
                }
                if (!closed) return ChatCommandParse.Malformed("unterminated quote")
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
}