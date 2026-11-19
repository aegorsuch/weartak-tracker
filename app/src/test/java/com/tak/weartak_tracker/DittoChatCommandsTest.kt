package com.tak.weartak_tracker

import com.tak.weartak_tracker.command.*
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotEventFramer
import com.tak.weartak_tracker.cot.DittoChatDocument
import com.tak.weartak_tracker.cot.InboundChat
import com.tak.weartak_tracker.cot.TakDatagramDecoder
import com.tak.weartak_tracker.service.ChatCommandRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DittoChatCommandsTest {
    private val now = 1_800_000_000_000L
    private fun native(
        message: String = "#weartakcommandsetcallsign value=BRAVO",
        room: String = "watch",
        id: String = "message",
        sender: String = "ADMIN",
    ): Map<String, Any?> = mapOf("_id" to id, "_v" to 2, "_r" to false,
        "d" to "admin-device", "e" to sender, "a" to "remote-peer", "b" to now,
        "authorType" to "a-f-G-U-C", "authorLoc" to "38.1,-77.2,NaN,HAE,NaN,NaN",
        "roomId" to room, "room" to "Operations", "parent" to "RootContactGroup", "msg" to message)

    @Test
    fun nativeAtakAuthorAndMessageFieldsAndSixFieldNanLocationAreAccepted() {
        val request = DittoChatDocument.request(native(), "watch", "local-peer", now)!!
        assertEquals("message", request.messageId)
        assertEquals("admin-device", request.senderUid)
        assertEquals("ADMIN", request.senderCallsign)
        assertEquals(now, request.sentAtMs)
        assertNull(request.replyRoomId)
        assertNull(DittoChatDocument.request(native(), "admin-device", "local-peer", now))
        assertNull(DittoChatDocument.request(native(), "watch", "remote-peer", now))
        assertNull(DittoChatDocument.request(native() + ("b" to now - 300_001), "watch", "local-peer", now))
        assertTrue(runCatching { DittoChatDocument.request(native() - "b", "watch", "local", now) }.isFailure)
        assertTrue(runCatching {
            DittoChatDocument.request(native() + ("o" to "invalid"), "watch", "local", now)
        }.isFailure)
        assertTrue(runCatching {
            DittoChatDocument.request(native() + ("authorLoc" to "NaN,-77"), "watch", "local", now)
        }.isFailure)
    }

    @Test
    fun allCommandNamesIncludingP12RunFromNativeDirectBroadcastAndGroup() = runBlocking {
        val commands = ProfileHandler.COMMAND_KEYS.keys.associateWith { "value=BRAVO" } +
            mapOf("loadprofile" to "name=ops", "enrollserver" to "name=ops", "setpdl" to "community",
                "toggleditto" to "on", "usep12" to ("ab".repeat(4096) + " server=ops"))
        val executed = mutableListOf<String>()
        val replies = mutableListOf<ChatCommandRequest>()
        val processor = ChatCommandProcessor({ true }, { listOf("ADMIN") },
            commands.keys.associateWith { name -> ChatCommandHandler { _, _ ->
                executed += name
                ChatCommandResult(true, "Updated")
            } }, { request, _ -> replies += request }, {}, { now })
        for (room in listOf("watch", "All Chat Rooms", "ops-group")) {
            for ((name, args) in commands) {
                val request = DittoChatDocument.request(native("#weartakcommand$name $args", room, "$room-$name"),
                    "watch", "local", now, listOf("watch", "admin-device"))!!
                processor.handle(request)
                assertEquals(name, executed.last())
                val reply = DittoChatDocument.encode(ChatCommandRuntime.replyXml("watch", "WATCH", replies.last(), "Updated"), "local")
                assertEquals(if (room == "watch") "admin-device" else room, reply["roomId"])
                assertEquals("Updated", reply["msg"])
                assertEquals("watch", reply["d"])
                assertEquals("WATCH", reply["e"])
                assertNotEquals("watch", reply["_id"])
            }
        }
        assertEquals(commands.size * 3, executed.size)
    }

    @Test
    fun unauthorizedSendersAreRejectedInEveryRoomAndCommandsOffIsRespected() = runBlocking {
        var runs = 0
        val processor = ChatCommandProcessor({ true }, { listOf("ADMIN") },
            mapOf("usep12" to ChatCommandHandler { _, _ -> runs++; ChatCommandResult(true, "Updated") }),
            { _, _ -> fail("Unknown sender must not receive an ack") }, {}, { now })
        for (room in listOf("watch", "All Chat Rooms", "group")) {
            processor.handle(DittoChatDocument.request(native("#weartakcommandusep12 ab", room, room, "UNKNOWN"),
                "watch", "local", now, listOf("watch", "admin-device"))!!)
        }
        val disabled = ChatCommandProcessor({ false }, { listOf("ADMIN") }, emptyMap(),
            { _, _ -> fail("Disabled") }, {}, { now })
        assertFalse(disabled.handle(DittoChatDocument.request(native(), "watch", "local", now)!!))
        assertEquals(0, runs)
    }

    @Test
    fun sameMessageAcrossDittoServerAndMulticastExecutesAndRepliesOnceEvenAfterRuntimeRecreation() = runBlocking {
        var runs = 0
        val replies = mutableListOf<String>()
        val journal = mutableSetOf<String>()
        fun processor() = ChatCommandProcessor({ true }, { listOf("ADMIN") },
            mapOf("usep12" to ChatCommandHandler { _, _ -> runs++; ChatCommandResult(true, "Updated") }),
            { req, _ -> replies += req.sourceServerId }, {}, { now },
            authorizer = ChatCommandAuthorizer(claim = { key, _ -> journal.add(key) }))
        val text = "#weartakcommandusep12 " + "ab".repeat(4096)
        val ditto = DittoChatDocument.request(native(text), "watch", "local", now)!!
        val xml = CotBuilder.chat("admin-device", "ADMIN", "watch", "WATCH", text, "message", now)
        val framed = CotEventFramer().let { it.feed(xml.take(8192)) + it.feed(xml.drop(8192)) }.single()
        val server = InboundChat.commandRequest(framed, "watch", "server-ops", now)!!
        val mesh = InboundChat.commandRequest(TakDatagramDecoder.decode(xml.toByteArray())!!,
            "watch", "multicast:224.10.10.1:17012", now)!!
        val first = processor()
        first.handle(ditto)
        first.handle(server)
        first.handle(mesh)
        processor().handle(ditto)
        assertEquals(1, runs)
        assertEquals(listOf("ditto:chat"), replies)
    }

    @Test
    fun p12AndExplicitDittoStatesAreAuthorizedInEveryRoomOnEveryChatRoute() = runBlocking {
        val runs = mutableListOf<String>()
        val processor = ChatCommandProcessor({ true }, { listOf("ADMIN") },
            listOf("usep12", "toggleditto").associateWith { ChatCommandHandler { _, req ->
                runs += req.sourceServerId; ChatCommandResult(true, "Updated")
            } }, { _, _ -> }, {}, { now })
        for (name in listOf("usep12", "toggleditto")) for (sender in listOf("ADMIN", "UNKNOWN")) {
            for (room in listOf("watch", "All Chat Rooms", "ops-group")) {
                for (route in listOf("ditto:chat", "server-ops", "multicast:224.10.10.1:17012")) {
                    val id = "$name-$sender-$room-$route"
                    val message = if (name == "usep12") "#weartakcommandusep12 ab server=ops"
                        else "#weartakcommandtoggleditto on"
                    val request = if (route == "ditto:chat") {
                        DittoChatDocument.request(native(message, room, id, sender), "watch", "local", now,
                            listOf("watch", "admin-device"))!!
                    } else {
                        val xml = CotBuilder.chat("admin-device", sender, room, room, message, id, now,
                            groupMembers = if (room == "watch") null else listOf("watch"))
                        InboundChat.commandRequest(xml, "watch", route, now)!!
                    }
                    processor.handle(request)
                }
            }
        }
        assertEquals(18, runs.size)
        assertEquals(6, runs.count { it == "ditto:chat" })
        assertEquals(6, runs.count { it == "server-ops" })
        assertEquals(6, runs.count { it.startsWith("multicast:") })
    }

    @Test
    fun replyRoutingNeverFansOutToOtherTransportsOrServers() = runBlocking {
        for (source in listOf("ditto:chat", "multicast:224.10.10.1:17012", "server-ops")) {
            val routes = mutableListOf<String>()
            assertEquals(1, ChatCommandRuntime.routeReply(source, "ack",
                { routes += "ditto:chat"; 1 }, { routes += source; 1 },
                { id, _ -> routes += id; 1 }))
            assertEquals(listOf(source), routes)
        }
    }

    @Test
    fun replyRoutingPreservesZeroWriteResultForDisableSafetyChecks() = runBlocking {
        for (source in listOf("ditto:chat", "multicast:224.10.10.1:17012", "server-ops")) {
            val routes = mutableListOf<String>()
            assertEquals(0, ChatCommandRuntime.routeReply(source, "warning",
                { routes += "ditto:chat"; 0 }, { routes += source; 0 },
                { id, _ -> routes += id; 0 }))
            assertEquals(listOf(source), routes)
        }
    }

    @Test
    fun nativeOtherDeviceDirectMessagesAndUnknownGroupsAreNotMisclassifiedAsAddressedCommands() {
        assertNull(DittoChatDocument.request(native(room = "someone-else"), "watch", "local", now))
        assertNull(DittoChatDocument.request(native(room = "admin-device"), "watch", "local", now))
        assertNull(DittoChatDocument.request(native(room = "unknown-group"), "watch", "local", now))
        assertNotNull(DittoChatDocument.request(native(room = "known-group"), "watch", "local", now,
            listOf("watch", "admin-device")))
    }

    @Test
    fun mismatchedLosslessDetailIsRejectedAndCompletedOversizedStreamEventsAreDropped() {
        val bad = native() + ("r" to "<detail><__chat id='watch' messageId='different' senderCallsign='ADMIN' chatroom='Operations'>" +
            "<chatgrp uid0='admin-device'/></__chat><remarks>not the message</remarks></detail>")
        assertTrue(runCatching { DittoChatDocument.request(bad, "watch", "local", now) }.isFailure)
        assertTrue(CotEventFramer(100).feed("<event>" + "a".repeat(101) + "</event>").isEmpty())
    }
}
