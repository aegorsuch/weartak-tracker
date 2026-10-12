package com.tak.weartak_tracker.cot

import com.tak.weartak_tracker.command.ChatCommandParser
import com.tak.weartak_tracker.command.ChatCommandRequest
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
    private val TIME = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Returns a command request when [xml] is a direct chat message to [myUid] that starts with the
     * command prefix. Group and broadcast chat, and everything else, return null.
     */
    fun commandRequest(xml: String, myUid: String, serverId: String, receivedAt: Long): ChatCommandRequest? {
        if (!xml.contains("<__chat") || !xml.contains(ChatCommandParser.PREFIX, ignoreCase = true)) return null
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        } catch (error: Exception) {
            return null
        }
        val event = doc.documentElement.takeIf { it.tagName == "event" } ?: return null
        val chat = event.getElementsByTagName("__chat").item(0) as? Element ?: return null
        val group = chat.getElementsByTagName("chatgrp").item(0) as? Element ?: return null
        val senderUid = group.getAttribute("uid0").takeIf { it.isNotBlank() && it != myUid } ?: return null
        val senderCallsign = chat.getAttribute("senderCallsign").takeIf { it.isNotBlank() } ?: return null
        val roomId = chat.getAttribute("id").ifBlank { group.getAttribute("id") }
        if (roomId != myUid) return null
        val remarks = event.getElementsByTagName("remarks").item(0) as? Element ?: return null
        val text = remarks.textContent.trim()
        if (!text.startsWith(ChatCommandParser.PREFIX, ignoreCase = true)) return null
        val sentAt = runCatching { TIME.parse(event.getAttribute("time").ifBlank { remarks.getAttribute("time") })?.time }
            .getOrNull() ?: 0L
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
}
