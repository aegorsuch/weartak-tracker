package com.tak.weartak_tracker

import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandParse
import com.tak.weartak_tracker.command.ChatCommandParser
import com.tak.weartak_tracker.command.ChatCommandProcessor
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.DittoPdlApplyResult
import com.tak.weartak_tracker.command.DittoPdlStore
import com.tak.weartak_tracker.command.DittoPdlUpdate
import com.tak.weartak_tracker.command.ProfileHandler
import com.tak.weartak_tracker.command.SetPdlHandler
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

class SetPdlTest {
    private val pdl = pdl()
    private var update: DittoPdlUpdate? = null
    private var applied = true
    private var locked = false
    private var storeCalls = 0
    private val handler = SetPdlHandler(
        communityIdentity = { pdl },
        store = DittoPdlStore {
            storeCalls++
            update = it
            DittoPdlApplyResult(applied)
        },
        networkLocked = { locked },
        now = { 1_000L },
    )
    private val request = ChatCommandRequest("u1", "ODIN", "", "m1", 1_000L, 1_000L, "s1")

    private fun execute(value: String) = runBlocking {
        handler.execute(ChatCommand("setpdl", mapOf("pdl" to value)), request)
    }

    @Test
    fun selectsValidatedCommunityAndRepliesWithOnlyMetadata() {
        val result = execute("community")
        assertTrue(result.message, result.ok)
        assertEquals("Ditto updated: source=community app=test-app expiry=2096-10-02T07:06:40Z", result.message)
        assertEquals(DittoPdlSource.COMMUNITY, update!!.source)
        assertNull(update!!.customPdl)
    }

    @Test
    fun savesCustomIdentityWithoutDisclosingCredentials() {
        val result = execute(pdl)
        assertTrue(result.message, result.ok)
        assertEquals(DittoPdlSource.CUSTOM, update!!.source)
        assertEquals(pdl, update!!.customPdl)
        assertEquals("Ditto updated: source=custom app=test-app expiry=2096-10-02T07:06:40Z", result.message)
        assertFalse(result.message.contains(pdl))
        assertFalse(update.toString().contains(pdl))
    }

    @Test
    fun rejectsMissingMalformedExpiredAndWrongTypeIdentities() {
        listOf("", "secret-invalid-pdl", pdl(expiry = 1_000L), pdl(type = "OTHER"),
            pdl(version = 1), "pdl=$pdl", "source=community",
            "x".repeat(ChatCommandParser.MAX_PDL_LENGTH + 1),
        ).forEach {
            val result = execute(it)
            assertFalse(result.ok)
            assertEquals("Ditto not changed: invalid or expired identity", result.message)
        }
        assertEquals(0, storeCalls)
    }

    @Test
    fun validatesBuiltInIdentityToo() = runBlocking {
        val unavailable = SetPdlHandler({ "" }, { error("must not save") }, { false }, { 1_000L })
        val result = unavailable.execute(ChatCommand("setpdl", mapOf("pdl" to "community")), request)
        assertEquals("Ditto not changed: invalid or expired identity", result.message)
    }

    @Test
    fun lockAndRuntimeFailureAreNotReportedAsSuccess() {
        locked = true
        assertEquals("Ditto not changed: settings locked", execute(pdl).message)
        assertEquals(0, storeCalls)
        locked = false
        applied = false
        assertEquals("Ditto update failed", execute(pdl).message)
    }

    @Test
    fun positionalPdlUsesDedicatedLimitAndBothCommandPrefixes() {
        val large = "a".repeat(ChatCommandParser.MAX_PDL_LENGTH)
        listOf(ChatCommandParser.COMMON_PREFIX, ChatCommandParser.PREFIX).forEach { prefix ->
            listOf("community", pdl, large).forEach { value ->
                val parsed = ChatCommandParser.parse("${prefix}setpdl $value") as ChatCommandParse.Parsed
                assertEquals("setpdl", parsed.command.name)
                assertEquals(mapOf("pdl" to value), parsed.command.args)
            }
            assertTrue(ChatCommandParser.parse("${prefix}setpdl ${large}a") is ChatCommandParse.Malformed)
        }
        assertTrue(ChatCommandParser.parse("#weartakcommandsetcallsign callsign=$large") is ChatCommandParse.Malformed)
        assertTrue(ChatCommandParser.parse("#weartakcommandsetpdl x" +
            " ".repeat(ChatCommandParser.MAX_PDL_COMMAND_LENGTH) + "x") is ChatCommandParse.Malformed)
        val profile = ChatCommandParser.parse("#weartakcommandsetteam team=Blue") as ChatCommandParse.Parsed
        assertEquals("setteam", profile.command.name)
    }

    @Test
    fun missingOrMultiplePositionalArgumentsUseTheSameRedactedUsageReply() = runBlocking {
        val replies = mutableListOf<String>()
        val processor = processor(replies = { _, message -> replies.add(message) })
        listOf("", "community enabled=true", "$pdl another").forEachIndexed { i, args ->
            processor.handle(request.copy(text = "#weartakcommandsetpdl $args", messageId = "m$i"))
        }
        assertEquals(List(3) { "Command rejected: setpdl needs community or one encoded PDL" }, replies)
        assertEquals(0, storeCalls)
    }

    @Test
    fun allowListedFreshDirectCommandsApplyOnceWithoutSecretAuditsOrReplies() = runBlocking {
        val replies = mutableListOf<String>()
        val audit = mutableListOf<String>()
        val processor = processor({ _, message -> replies.add(message) }, audit::add)
        val text = "#weartakcommandsetpdl $pdl"
        processor.handle(requireNotNull(inbound("tracker", "TRACKER", "UNKNOWN", text, "other")))
        assertTrue(replies.isEmpty())
        processor.handle(requireNotNull(inbound("tracker", "TRACKER", "ODIN", text, "stale", -1_000_000L)))
        assertEquals(0, storeCalls)
        val direct = requireNotNull(inbound("tracker", "TRACKER", "ODIN", text, "ok"))
        processor.handle(direct)
        processor.handle(direct)
        assertEquals(1, storeCalls)
        assertEquals(2, replies.size)
        assertTrue((audit + replies).none { it.contains(pdl) || it.contains("test-secret") })
        val reply = ChatCommandRuntime.replyXml("tracker", "TRACKER", direct, replies.last())
        assertTrue(reply.contains("id='sender'"))
    }

    @Test
    fun allCommandsAcceptAuthorizedBroadcastAndMemberGroupsAndReplyToThatConversation() = runBlocking {
        val replies = mutableListOf<String>()
        var team = ""
        var profileCalls = 0
        val profile = ProfileHandler(setOf("team"), { profileCalls++; team = it.team.orEmpty() })
        val processor = ChatCommandProcessor(
            { true }, { listOf("ODIN") }, mapOf("setpdl" to handler, "setteam" to profile),
            { origin, message -> replies.add(ChatCommandRuntime.replyXml("tracker", "TRACKER", origin, message)) },
            {}, now = { 1_000L },
        )
        listOf("All Chat Rooms" to "All Chat Rooms", "group-uid" to "OPS").forEach { (room, title) ->
            listOf("#weartakcommandsetpdl $pdl", "#weartakcommandsetteam team=Blue").forEachIndexed { i, text ->
                val count = replies.size
                processor.handle(requireNotNull(inbound(room, title, "UNKNOWN", text, "$room-$i-unknown")))
                assertEquals(count, replies.size)
                val allowed = requireNotNull(inbound(room, title, "ODIN", text, "$room-$i"))
                assertEquals(room, allowed.replyRoomId)
                assertEquals(title, allowed.replyRoomName)
                processor.handle(allowed)
                assertEquals(count + 1, replies.size)
                val reply = replies.last()
                assertTrue(reply.contains("chatroom='$title' id='$room'"))
                assertFalse(reply.contains(pdl))
                if (room == "All Chat Rooms") {
                    assertTrue(reply.contains("uid1='All Chat Rooms'"))
                    assertFalse(reply.contains("<marti"))
                } else {
                    assertTrue(reply.contains("<dest uid='sender'"))
                    assertTrue(reply.contains("<dest uid='peer-2'"))
                    assertFalse(reply.contains("<dest uid='group-uid'"))
                }
            }
        }
        assertEquals(2, storeCalls)
        assertEquals(2, profileCalls)
        assertEquals("Blue", team)
        val notMember = CotBuilder.chat("sender", "ODIN", "other-room", "OTHER",
            "#weartakcommandsetteam team=Blue", "not-member", 1_000L, groupMembers = listOf("peer-2"))
        assertNull(InboundChat.commandRequest(notMember, "tracker", "s1", 1_000L))
    }

    @Test
    fun disabledCommandsAndLegacyHandlerDoNotApply() = runBlocking {
        val disabled = ChatCommandProcessor(
            { false }, { listOf("ODIN") }, mapOf("setpdl" to handler), { _, _ -> fail() }, {},
            now = { 1_000L },
        )
        disabled.handle(request.copy(text = "#weartakcommandsetpdl community"))
        val replies = mutableListOf<String>()
        processor(replies = { _, message -> replies.add(message) })
            .handle(request.copy(text = "#weartakcommandsetdittopdl source=community"))
        assertEquals(listOf("Unknown command: setdittopdl"), replies)
        assertEquals(0, storeCalls)
    }

    @Test
    fun applyExceptionIsRedactedInReplyAndAudit() = runBlocking {
        val replies = mutableListOf<String>()
        val audit = mutableListOf<String>()
        val failing = SetPdlHandler({ pdl }, { throw IllegalStateException(pdl) }, { false }, { 1_000L })
        val processor = ChatCommandProcessor(
            { true }, { listOf("ODIN") }, mapOf("setpdl" to failing),
            { _, message -> replies.add(message) }, audit::add, now = { 1_000L },
        )
        processor.handle(request.copy(text = "#weartakcommandsetpdl $pdl"))
        assertEquals(listOf("Ditto update failed"), replies)
        assertTrue(audit.none { it.contains(pdl) })
    }

    private fun processor(
        replies: suspend (ChatCommandRequest, String) -> Unit,
        audit: (String) -> Unit = {},
    ) = ChatCommandProcessor(
        { true }, { listOf("ODIN") }, mapOf("setpdl" to handler), replies, audit, now = { 1_000L },
    )

    private fun inbound(room: String, title: String, sender: String, text: String, id: String, sentAt: Long = 1_000L) =
        InboundChat.commandRequest(
            CotBuilder.chat("sender", sender, room, title, text, id, sentAt,
                groupMembers = if (room == "tracker") null else listOf("tracker", "peer-2")),
            "tracker", "s1", 1_000L,
        )

    private fun pdl(expiry: Long = 4_000_000_000_000L, type: String = "SK", version: Int = 2): String {
        val json = """{"v":$version,"it":"$type","a":"test-app-id","s":"test-secret-key","t":"test-secret-token","e":$expiry}"""
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(json.toByteArray()) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }
}
