package com.tak.weartak_tracker.cot

import com.tak.weartak_tracker.command.ChatCommandParser
import com.tak.weartak_tracker.command.ChatCommandRequest
import org.w3c.dom.Element
import org.xml.sax.SAXException
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
            if (start >= 0 && end + END.length - start <= maxBytes) events += buffer.substring(start, end + END.length)
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
     * Returns a command request from a direct, broadcast or member-group chat. Like WearOS, a direct
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
        if (!xml.contains("<__chat") || (
                !xml.contains(ChatCommandParser.PREFIX, ignoreCase = true) &&
                    !xml.contains(ChatCommandParser.COMMON_PREFIX, ignoreCase = true)
            )) return null
        fun drop(reason: String): ChatCommandRequest? {
            onDrop("$reason (source $serverId)")
            return null
        }
        val content = xml.trim { it.isWhitespace() || it == '\uFEFF' || it == '\u0000' }
        if (content.contains("<!DOCTYPE", ignoreCase = true)) return drop("chat XML has a DOCTYPE")
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isExpandEntityReferences = false
                // Android's parser rejects these JAXP features; DOCTYPE is refused above and by the resolver.
                listOf(
                    "http://apache.org/xml/features/disallow-doctype-decl" to true,
                    "http://xml.org/sax/features/external-general-entities" to false,
                    "http://xml.org/sax/features/external-parameter-entities" to false,
                ).forEach { (feature, enabled) ->
                    try {
                        setFeature(feature, enabled)
                    } catch (_: Exception) {
                    }
                }
            }
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw SAXException("External entities are prohibited") }
            }.parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
        } catch (error: Exception) {
            return drop("unparseable chat XML (${error.javaClass.simpleName})")
        }
        val event = doc.documentElement.takeIf { it.tagName == "event" } ?: return drop("no event element")
        val chat = event.getElementsByTagName("__chat").item(0) as? Element ?: return drop("no __chat element")
        val group = chat.getElementsByTagName("chatgrp").item(0) as? Element ?: return drop("no chatgrp element")
        val senderUid = group.getAttribute("uid0").takeIf { it.isNotBlank() } ?: return drop("no sender uid")
        if (senderUid == myUid) return null
        val senderCallsign = chat.getAttribute("senderCallsign").takeIf { it.isNotBlank() }
            ?: return drop("no sender callsign")
        val remarks = event.getElementsByTagName("remarks").item(0) as? Element ?: return drop("no remarks")
        val text = remarks.textContent.trim()
        if (!ChatCommandParser.hasCommandPrefix(text)) return drop("text does not start with the prefix")
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
        if (!direct && roomId.isBlank()) return drop("no chat conversation")
        if (!direct && roomId != "All Chat Rooms" && myUid !in participants) return drop("not a member of the chat group")
        val sentAt = parseTime(event.getAttribute("time")) ?: parseTime(remarks.getAttribute("time")) ?: 0L
        return ChatCommandRequest(
            senderUid = senderUid,
            senderCallsign = senderCallsign,
            text = text,
            messageId = chat.getAttribute("messageId").takeIf { it.isNotBlank() },
            sentAtMs = sentAt,
            receivedAtMs = receivedAt,
            sourceServerId = serverId,
            replyRoomId = roomId.takeUnless { direct },
            replyRoomName = chat.getAttribute("chatroom").ifBlank { roomId }.takeUnless { direct },
            replyMemberUids = if (direct) emptyList() else (listOf(senderUid) + participants)
                .filter { it != myUid && it != roomId }.distinct(),
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
