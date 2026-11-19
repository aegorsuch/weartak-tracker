package com.tak.weartak_tracker.cot

import com.tak.weartak_tracker.command.ChatCommandRequest
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Native ATAK Edge Sync 1.1.1 chat aliases, not a track document or a raw command envelope. */
internal object DittoChatDocument {
    const val MAX_MESSAGE_BYTES = 36 * 1024
    const val TTL_MS = 86_400_000L

    fun request(
        raw: Map<String, Any?>,
        myUid: String,
        localPeer: String,
        now: Long,
        knownGroupMembers: List<String> = emptyList(),
    ): ChatCommandRequest? {
        if (raw["_r"] == true || raw["visible"] == false || raw["f"] == false) return null
        require((raw["_v"] as? Number)?.toDouble() == 2.0 && raw["_r"] == false)
        fun text(key: String): String = (raw[key] as? String)?.also {
            require(it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl))
        } ?: throw IllegalArgumentException("Missing chat metadata")
        val id = text("_id")
        val sender = text("d")
        val callsign = text("e")
        val peer = text("a")
        if (sender == myUid || peer == localPeer) return null
        val roomId = text("roomId")
        val room = text("room")
        text("parent")
        text("authorType")
        val message = raw["msg"] as? String ?: throw IllegalArgumentException("Missing chat message")
        require(message.isNotBlank() && message.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES)
        val timestamp = (raw["b"] as? Number)?.toDouble() ?: throw IllegalArgumentException("Missing chat time")
        require(timestamp.isFinite() && timestamp > 0 && timestamp <= 253402214399999.0 &&
            timestamp == timestamp.toLong().toDouble())
        val time = timestamp.toLong()
        if (now - time > 5 * 60_000L || time - now > 5 * 60_000L) return null
        val expiry = if ("o" !in raw) time + TTL_MS else {
            val value = (raw["o"] as? Number)?.toDouble() ?: throw IllegalArgumentException("Invalid chat expiry")
            require(value.isFinite() && value > 0 && value <= 253402300799999.0 &&
                value == value.toLong().toDouble())
            value.toLong()
        }
        require(expiry >= time && expiry <= time + TTL_MS)
        if (expiry <= now) return null
        // ATAK GeoPoint.toString(6): latitude,longitude,altitude,ALTREF,CE,LE.
        // Optional altitude/error values may be NaN; only horizontal coordinates are required.
        val location = text("authorLoc").split(',').map(String::trim)
        require(location.size >= 2)
        val lat = location[0].toDoubleOrNull()
        val lon = location[1].toDoubleOrNull()
        require(lat != null && lat.isFinite() && lat in -90.0..90.0 &&
            lon != null && lon.isFinite() && lon in -180.0..180.0)

        var members = knownGroupMembers
        if ("r" in raw) {
            val detail = raw["r"] as? String ?: throw IllegalArgumentException("Invalid chat detail")
            require(detail.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES &&
                !detail.contains("<!DOCTYPE", true) && !detail.contains("<!ENTITY", true))
            val root = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
                .newDocumentBuilder().apply {
                    setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities prohibited") }
                }.parse(ByteArrayInputStream(detail.toByteArray(Charsets.UTF_8))).documentElement
            require(root.tagName == "detail" && root.getElementsByTagName("__chat").length == 1 &&
                root.getElementsByTagName("chatgrp").length == 1 && root.getElementsByTagName("remarks").length == 1)
            val chat = root.getElementsByTagName("__chat").item(0) as Element
            val group = root.getElementsByTagName("chatgrp").item(0) as Element
            val remarks = root.getElementsByTagName("remarks").item(0) as Element
            require(chat.getAttribute("messageId") == id && group.getAttribute("uid0") == sender &&
                chat.getAttribute("senderCallsign") == callsign && chat.getAttribute("id") == roomId &&
                chat.getAttribute("chatroom") == room && remarks.textContent == message)
            members = (0 until group.attributes.length).map { group.attributes.item(it) }
                .filter { it.nodeName.matches(Regex("uid[0-9]+")) }.map { it.nodeValue }.distinct()
        }
        // A native room alone cannot distinguish a named group from a DM to another device.
        val direct = roomId == myUid || (roomId == sender && myUid in members)
        if (!direct && roomId != "All Chat Rooms" && myUid !in members) return null
        return ChatCommandRequest(sender, callsign, message, id, time, now, "ditto:chat",
            replyRoomId = roomId.takeUnless { direct },
            replyRoomName = room.takeUnless { direct },
            replyMemberUids = members.filter { it != myUid && it != roomId })
    }

    fun encode(xml: String, peer: String): Map<String, Any?> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val event = doc.documentElement
        val chat = event.getElementsByTagName("__chat").item(0) as Element
        val group = chat.getElementsByTagName("chatgrp").item(0) as Element
        val remarks = event.getElementsByTagName("remarks").item(0) as Element
        val time = Instant.parse(event.getAttribute("time")).toEpochMilli()
        return mapOf("_id" to chat.getAttribute("messageId"), "_v" to 2, "_r" to false,
            "a" to peer, "b" to time, "d" to group.getAttribute("uid0"),
            "e" to chat.getAttribute("senderCallsign"), "msg" to remarks.textContent,
            "room" to chat.getAttribute("chatroom"), "roomId" to chat.getAttribute("id"),
            "parent" to chat.getAttribute("parent"), "authorType" to "a-f-G-U-C",
            "authorLoc" to "0.0,0.0,0.0", "o" to time + TTL_MS)
    }
}
