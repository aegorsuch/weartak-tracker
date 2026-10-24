package com.tak.weartak_tracker

import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandParse
import com.tak.weartak_tracker.command.ChatCommandParser
import com.tak.weartak_tracker.command.ChatCommandProcessor
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.DittoPdlApplyResult
import com.tak.weartak_tracker.command.DittoPdlStore
import com.tak.weartak_tracker.command.DittoPdlUpdate
import com.tak.weartak_tracker.command.SetDittoPdlHandler
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.InboundChat
import com.tak.weartak_tracker.data.DittoPdlSource
import com.tak.weartak_tracker.service.ChatCommandRuntime
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SetDittoPdlTest {
    private val pdl = pdl()
    private var update: DittoPdlUpdate? = null
    private var enabled = false
    private var applied = true
    private var locked = false
    private var storeCalls = 0
    private val handler = SetDittoPdlHandler(
        communityIdentity = { pdl },
        store = DittoPdlStore {
            storeCalls++
            update = it
            enabled = it.enabled ?: enabled
            DittoPdlApplyResult(enabled, applied)
        },
        networkLocked = { locked },
        now = { 1_000L },
    )
    private val request = ChatCommandRequest("u1", "ODIN", "", "m1", 1_000L, 1_000L, "s1")

    private fun execute(vararg args: Pair<String, String>) = runBlocking {
        handler.execute(ChatCommand("setdittopdl", args.toMap()), request)
    }

    @Test
    fun selectsValidatedCommunityAndPreservesEnabledWhenOmitted() {
        enabled = true
        val result = execute("source" to "community")
        assertTrue(result.message, result.ok)
        assertEquals("Ditto updated: source=community app=test-app expiry=2096-10-02T07:06:40Z enabled=true", result.message)
        assertEquals(DittoPdlSource.COMMUNITY, update!!.source)
        assertNull(update!!.customPdl)
        assertNull(update!!.enabled)
    }

    @Test
    fun savesCustomIdentityAndOptionalEnabledWithoutDisclosingCredentials() {
        val result = execute("pdl" to pdl, "enabled" to "true")
        assertTrue(result.message, result.ok)
        assertEquals(DittoPdlSource.CUSTOM, update!!.source)
        assertEquals(pdl, update!!.customPdl)
        assertTrue(enabled)
        assertTrue(result.message.endsWith("enabled=true"))
        assertFalse(result.message.contains(pdl))
        assertFalse(update.toString().contains(pdl))
        assertTrue(execute("source" to "community", "enabled" to "false").ok)
        assertFalse(enabled)
    }

    @Test
    fun rejectsInvalidGrammarBeforeChangingAnything() {
        listOf(
            emptyMap(),
            mapOf("enabled" to "true"),
            mapOf("source" to "custom"),
            mapOf("source" to "community", "pdl" to pdl),
            mapOf("source" to "community", "enabled" to "1"),
            mapOf("pdl" to pdl, "unknown" to "secret"),
        ).forEach { args ->
            val result = runBlocking { handler.execute(ChatCommand("setdittopdl", args), request) }
            assertFalse(result.ok)
            assertEquals("Ditto not changed: invalid arguments", result.message)
        }
        assertEquals(0, storeCalls)
    }

    @Test
    fun rejectsMissingMalformedExpiredAndWrongTypeIdentities() {
        listOf("", "secret-invalid-pdl", pdl(expiry = 1_000L), pdl(type = "OTHER"),
            pdl(version = 1), "x".repeat(ChatCommandParser.MAX_PDL_LENGTH + 1),
        ).forEach {
            val result = execute("pdl" to it, "enabled" to "true")
            assertFalse(result.ok)
            assertEquals("Ditto not changed: invalid or expired identity", result.message)
        }
        assertEquals(0, storeCalls)
    }

    @Test
    fun validatesBuiltInIdentityToo() = runBlocking {
        val unavailable = SetDittoPdlHandler({ "" }, { error("must not save") }, { false }, { 1_000L })
        val result = unavailable.execute(ChatCommand("setdittopdl", mapOf("source" to "community")), request)
        assertEquals("Ditto not changed: invalid or expired identity", result.message)
    }

    @Test
    fun lockAndRuntimeFailureAreNotReportedAsSuccess() {
        locked = true
        assertEquals("Ditto not changed: settings locked", execute("pdl" to pdl).message)
        assertEquals(0, storeCalls)
        locked = false
        applied = false
        assertEquals("Ditto update failed", execute("pdl" to pdl).message)
    }

    @Test
    fun onlyDedicatedCommandAcceptsLargerPdlAndCommonPrefix() {
        val large = "a".repeat(ChatCommandParser.MAX_PDL_LENGTH)
        listOf("#weartakcommand", ChatCommandParser.PREFIX).forEach { prefix ->
            val parsed = ChatCommandParser.parse("${prefix}setdittopdl pdl=$large enabled=true")
            assertTrue(parsed is ChatCommandParse.Parsed)
            assertEquals(large, (parsed as ChatCommandParse.Parsed).command.args["pdl"])
            assertTrue(ChatCommandParser.parse("${prefix}setdittopdl pdl=${large}a") is ChatCommandParse.Malformed)
        }
        assertTrue(ChatCommandParser.parse("${ChatCommandParser.PREFIX}setcallsign callsign=$large") is ChatCommandParse.Malformed)
        assertTrue(ChatCommandParser.parse("${ChatCommandParser.PREFIX}setdittopdl source=${"a".repeat(129)}") is ChatCommandParse.Malformed)
        assertTrue(ChatCommandParser.parse("#weartakcommandsetcallsign callsign=X") is ChatCommandParse.NotACommand)
        assertTrue(ChatCommandParser.parse("#weartakcommandsetdittopdlnotreally pdl=x") is ChatCommandParse.NotACommand)
        assertTrue(ChatCommandParser.parse("#weartakcommandsetdittopdl pdl=x" +
            " ".repeat(ChatCommandParser.MAX_DITTO_COMMAND_LENGTH) + "enabled=true") is ChatCommandParse.Malformed)
    }

    @Test
    fun allowListedFreshDirectCommandsApplyOnceWithoutSecretAuditsOrReplies() = runBlocking {
        val replies = mutableListOf<String>()
        val audit = mutableListOf<String>()
        val processor = ChatCommandProcessor(
            { true }, { listOf("ODIN") }, mapOf("setdittopdl" to handler),
            { _, message -> replies.add(message) }, audit::add, now = { 1_000L },
        )
        fun inbound(to: String, sender: String, id: String, sentAt: Long = 1_000L) = InboundChat.commandRequest(
            CotBuilder.chat("sender", sender, to, "TRACKER",
                "#weartakcommandsetdittopdl pdl=$pdl enabled=true", id, sentAt),
            "tracker", "s1", 1_000L,
        )
        processor.handle(requireNotNull(inbound("tracker", "UNKNOWN", "other")))
        assertTrue(replies.isEmpty())
        processor.handle(requireNotNull(inbound("tracker", "ODIN", "stale", -1_000_000L)))
        assertEquals(0, storeCalls)
        val direct = requireNotNull(inbound("tracker", "ODIN", "ok"))
        processor.handle(direct)
        processor.handle(direct)
        assertEquals(1, storeCalls)
        assertEquals(2, replies.size)
        assertTrue(replies.last().startsWith("Ditto updated: source=custom app=test-app expiry="))
        assertTrue((audit + replies).none { it.contains(pdl) || it.contains("test-secret") })
    }

    @Test
    fun broadcastAndGroupRequireAllowListAndReplyInTheOriginalConversation() = runBlocking {
        val replies = mutableListOf<String>()
        val processor = ChatCommandProcessor(
            { true }, { listOf("ODIN") }, mapOf("setdittopdl" to handler),
            { origin, message -> replies.add(ChatCommandRuntime.replyXml("tracker", "TRACKER", origin, message)) },
            {}, now = { 1_000L },
        )
        listOf("All Chat Rooms" to "All Chat Rooms", "group-uid" to "OPS").forEach { (room, title) ->
            fun inbound(sender: String, message: String, id: String) = InboundChat.commandRequest(
                CotBuilder.chat("sender", sender, room, title, message, id, 1_000L,
                    groupMembers = listOf("tracker", "sender", "peer-2")),
                "tracker", "s1", 1_000L,
            )
            val before = storeCalls
            val text = "#weartakcommandsetdittopdl pdl=$pdl enabled=true"
            val disallowed = requireNotNull(inbound("UNKNOWN", text, "$room-unknown"))
            processor.handle(disallowed)
            assertEquals(before, storeCalls)
            assertEquals(before, replies.size)
            val allowed = requireNotNull(inbound("ODIN", text, "$room-allowed"))
            assertEquals(room, allowed.replyRoomId)
            assertEquals(title, allowed.replyRoomName)
            processor.handle(allowed)
            assertEquals(before + 1, storeCalls)
            val reply = replies.last()
            assertTrue(reply.contains("chatroom='$title' id='$room'"))
            assertTrue(reply.contains("id='$room'"))
            assertFalse(reply.contains(pdl))
            if (room == "All Chat Rooms") {
                assertTrue(reply.contains("uid1='All Chat Rooms'"))
                assertFalse(reply.contains("<marti"))
            }
            else {
                assertTrue(reply.contains("<dest uid='sender'"))
                assertTrue(reply.contains("<dest uid='peer-2'"))
                assertFalse(reply.contains("<dest uid='group-uid'"))
            }
            val notMember = CotBuilder.chat("sender", "ODIN", "other-room", "OTHER",
                "#weartakcommandsetdittopdl source=community", "not-member", 1_000L,
                groupMembers = listOf("sender", "peer-2"))
            assertNull(InboundChat.commandRequest(notMember, "tracker", "s1", 1_000L))
            assertNull(inbound("ODIN", "${ChatCommandParser.PREFIX}setcallsign callsign=NEW", "$room-profile"))
            assertNull(inbound("UNKNOWN", "${ChatCommandParser.PREFIX}loadprofile name=ops", "$room-load"))
        }
    }

    @Test
    fun malformedCommandIsRedactedAndDisabledCommandsDoNotApply() = runBlocking {
        val replies = mutableListOf<String>()
        val processor = ChatCommandProcessor(
            { true }, { listOf("ODIN") }, mapOf("setdittopdl" to handler),
            { _, message -> replies.add(message) }, {}, now = { 1_000L },
        )
        processor.handle(request.copy(text = "#weartakcommandsetdittopdl pdl=$pdl pdl=secret"))
        assertEquals(listOf("Ditto not changed: invalid arguments"), replies)
        val disabled = ChatCommandProcessor(
            { false }, { listOf("ODIN") }, mapOf("setdittopdl" to handler), { _, _ -> fail() }, {},
            now = { 1_000L },
        )
        disabled.handle(request.copy(text = "#weartakcommandsetdittopdl source=community"))
        assertEquals(0, storeCalls)
    }

    @Test
    fun applyExceptionIsRedactedInReplyAndAudit() = runBlocking {
        val replies = mutableListOf<String>()
        val audit = mutableListOf<String>()
        val failing = SetDittoPdlHandler({ pdl }, { throw IllegalStateException(pdl) }, { false }, { 1_000L })
        val processor = ChatCommandProcessor(
            { true }, { listOf("ODIN") }, mapOf("setdittopdl" to failing),
            { _, message -> replies.add(message) }, audit::add, now = { 1_000L },
        )
        processor.handle(request.copy(text = "#weartakcommandsetdittopdl pdl=$pdl"))
        assertEquals(listOf("Ditto update failed"), replies)
        assertTrue(audit.none { it.contains(pdl) })
    }

    private fun pdl(expiry: Long = 4_000_000_000_000L, type: String = "SK", version: Int = 2): String {
        val json = """{"v":$version,"it":"$type","a":"test-app-id","s":"test-secret-key","t":"test-secret-token","e":$expiry}"""
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(json.toByteArray()) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }
}
