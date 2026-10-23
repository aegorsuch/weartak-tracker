package com.tak.weartak_tracker.cot

import com.tak.weartak_tracker.command.ChatCommandParser
import com.tak.weartak_tracker.command.ChatCommandRequest
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

/** Splits a TAK Server byte stream into `<event>...</event>` messages, dropping anything oversized. */
class CotEventFramer(private val maxBytes: Int = 64 * 1024) {
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<String> {
        buffer.append(chunk)
        val events = mutableListOf<String>()
        while (true) {
            val end = buffer.indexOf(END)
            if (end < 0) break
            val start = buffer.lastIndexOf("<event", end)
            if (start >= 0) events += buffer.substring(start, end + END.length)
            buffer.delete(0, end + END.length)
        }
        if (buffer.length > maxBytes) buffer.setLength(0)
        return events
    }

    fun clear() = buffer.setLength(0)

    private companion object {
        const val END = "</event>"
    }
}

object InboundChat {
    /**
     * Returns a command request when [xml] is a direct chat message to this device that starts with the
     * command prefix. Group and broadcast chat, and everything else, return null. Like WearOS, a direct
     * message counts when its conversation id is this device, or when it is the sender's own id (ATAK's
     * reply form) and the chatgrp members or the remarks `to` name this device. [onDrop] gets the reason a
     * command-looking chat was ignored; it never includes message text.
     */
    fun commandRequest(
        xml: String,
        myUid: String,
        serverId: String,
        receivedAt: Long,
        myCallsign: String = "",
        onDrop: (String) -> Unit = {},
    ): ChatCommandRequest? {
        if (!xml.contains("<__chat") || !xml.contains(ChatCommandParser.PREFIX, ignoreCase = true)) return null
        fun drop(reason: String): ChatCommandRequest? {
            onDrop("$reason (source $serverId)")
            return null
        }
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (error: Exception) {
            return drop("unparseable chat XML")
        }
        val event = doc.documentElement.takeIf { it.tagName == "event" } ?: return drop("no event element")
        val chat = event.getElementsByTagName("__chat").item(0) as? Element ?: return drop("no __chat element")
        val group = chat.getElementsByTagName("chatgrp").item(0) as? Element ?: return drop("no chatgrp element")
        val senderUid = group.getAttribute("uid0").takeIf { it.isNotBlank() } ?: return drop("no sender uid")
        if (senderUid == myUid) return null
        val senderCallsign = chat.getAttribute("senderCallsign").takeIf { it.isNotBlank() }
            ?: return drop("no sender callsign")
        val remarks = event.getElementsByTagName("remarks").item(0) as? Element ?: return drop("no remarks")
        val roomId = chat.getAttribute("id").ifBlank { group.getAttribute("id") }
        val participants = group.attributes.let { attrs ->
            (0 until attrs.length).mapNotNull { attrs.item(it) }
                .filter { it.nodeName.matches(Regex("uid[1-9][0-9]*")) }
                .map { it.nodeValue }
        }
        val to = remarks.getAttribute("to")
        val addressedToMe = myUid in participants || to == myUid ||
            (myCallsign.isNotBlank() && to.equals(myCallsign, ignoreCase = true))
        val direct = roomId == myUid || ((roomId.isBlank() || roomId == senderUid) && addressedToMe)
        if (!direct) return drop("not a direct chat to this device (room ${roomId.take(64)})")
        val text = remarks.textContent.trim()
        if (!text.startsWith(ChatCommandParser.PREFIX, ignoreCase = true)) return drop("text does not start with the prefix")
        val sentAt = parseTime(event.getAttribute("time")) ?: parseTime(remarks.getAttribute("time")) ?: 0L
        return ChatCommandRequest(
            senderUid = senderUid,
            senderCallsign = senderCallsign,
            text = text,
            messageId = chat.getAttribute("messageId").takeIf { it.isNotBlank() },
            sentAtMs = sentAt,
            receivedAtMs = receivedAt,
            sourceServerId = serverId,
        )
    }

    /** Thread-safe ISO-8601 parse that accepts any number of fraction digits and offsets, as WearOS does. */
    fun parseTime(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }
}
