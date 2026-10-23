package com.tak.weartak_tracker

import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandHandler
import com.tak.weartak_tracker.command.ChatCommandProcessor
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.EnrollServerHandler
import com.tak.weartak_tracker.command.LoadProfileHandler
import com.tak.weartak_tracker.command.PresetServer
import com.tak.weartak_tracker.command.ProfileHandler
import com.tak.weartak_tracker.command.ProfilePresetParser
import com.tak.weartak_tracker.command.ProfileStore
import com.tak.weartak_tracker.command.ProfileUpdate
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotEventFramer
import com.tak.weartak_tracker.cot.InboundChat
import com.tak.weartak_tracker.cot.TakDatagramDecoder
import com.tak.weartak_tracker.data.MedicalProfile
import com.tak.weartak_tracker.data.MedicalProfileCodec
import com.tak.weartak_tracker.data.ProfilePresets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerCommandsTest {
    private val request = ChatCommandRequest("U1", "ODIN", "x", "m1", 0, 0, "s")

    private class Recorder : ProfileStore {
        var last: ProfileUpdate? = null
        override suspend fun apply(update: ProfileUpdate) {
            last = update
        }
    }

    private fun profile(rec: Recorder, name: String = "setprofile") =
        ProfileHandler(ProfileHandler.COMMAND_KEYS.getValue(name), rec, currentYear = { 2026 })

    private fun run(h: ChatCommandHandler, name: String, vararg args: Pair<String, String>) =
        runBlocking { h.execute(ChatCommand(name, args.toMap()), request) }

    @Test
    fun identityValidatesBeforeStoring() {
        val rec = Recorder()
        val h = profile(rec)
        assertFalse(run(h, "setprofile", "team" to "Dark Green", "role" to "Nope").ok)
        assertNull(rec.last)
        assertTrue(run(h, "setprofile", "team" to "dark green", "role" to "K9").ok)
        assertEquals(ProfileUpdate(team = "Dark Green", role = "K9"), rec.last)
    }

    @Test
    fun identityRejectsUnknownKeys() {
        val h = ProfileHandler(setOf("team"), Recorder())
        assertFalse(run(h, "setteam", "role" to "K9").ok)
    }

    @Test
    fun profileFieldsMatchWearOs() {
        val rec = Recorder()
        val h = profile(rec)
        val ok = run(
            h, "setprofile", "height" to "5'8\"", "weight" to "150", "birthyear" to "1988", "sex" to "male",
            "usertype" to "coalition civilian", "pants" to "30", "waist" to "30",
        )
        assertTrue(ok.message, ok.ok)
        assertEquals(
            ProfileUpdate(
                heightFeet = 5, heightInches = 8, weightLbs = 150, birthYear = 1988, sex = "Male",
                userType = "Coalition Civilian", pantsLengthInches = 30, waistInches = 30,
            ),
            rec.last,
        )
        rec.last = null
        listOf(
            "height" to "9'0", "height" to "5'12", "weight" to "0", "birthyear" to "2027", "sex" to "X",
            "usertype" to "Alien", "pants" to "19", "pants" to "51", "waist" to "61",
        ).forEach { assertFalse(it.toString(), run(h, "setprofile", it).ok) }
        assertNull(rec.last)
    }

    @Test
    fun longPantsAndWaistNamesAreAliases() {
        val rec = Recorder()
        assertTrue(run(profile(rec, "setuniformpantslengthinches"), "setuniformpantslengthinches", "pants" to "32").ok)
        assertEquals(32, rec.last?.pantsLengthInches)
        assertTrue(run(profile(rec, "setwaistsize"), "setwaistsize", "waist" to "34").ok)
        assertEquals(34, rec.last?.waistInches)
    }

    @Test
    fun enrollServerAddsWithoutEchoingCredentials() {
        val added = mutableListOf<PresetServer>()
        val h = EnrollServerHandler({ servers -> added += servers; servers.size }, { false })
        val r = run(h, "enrollserver", "name" to "OPS", "url" to "tak.example.org", "port" to "8089",
            "username" to "alice", "password" to "s3cret;x")
        assertTrue(r.message, r.ok)
        assertEquals(PresetServer("OPS", "tak.example.org", 8089, "alice", "s3cret;x"), added.single())
        assertFalse(r.message.contains("alice") || r.message.contains("s3cret"))
        assertFalse(run(h, "enrollserver", "name" to "OPS", "url" to "tak.example.org").ok)
        val locked = EnrollServerHandler({ error("must not add") }, { true })
        assertFalse(run(locked, "enrollserver", "name" to "OPS", "url" to "h", "username" to "u", "password" to "p").ok)
        val dup = EnrollServerHandler({ 0 }, { false })
        assertFalse(run(dup, "enrollserver", "name" to "OPS", "url" to "h", "username" to "u", "password" to "p").ok)
    }

    @Test
    fun processorAcceptsBareAndSemicolonValues() {
        val rec = Recorder()
        val added = mutableListOf<PresetServer>()
        val replies = mutableListOf<String>()
        val handlers = ProfileHandler.COMMAND_KEYS.mapValues { (_, k) -> ProfileHandler(k, rec) } +
            ("enrollserver" to EnrollServerHandler({ s -> added += s; s.size }, { false }))
        val p = ChatCommandProcessor(
            isEnabled = { true }, allowedCallsigns = { listOf("ODIN") }, handlers = handlers,
            reply = { _, m -> replies += m }, audit = {}, now = { 1_000 },
            positional = ProfileHandler.POSITIONAL_KEYS,
        )
        fun send(id: String, text: String) =
            runBlocking { p.handle(ChatCommandRequest("U1", "ODIN", text, id, 1_000, 1_000, "multicast:x")) }
        send("a", "#weartaktrackercommandsetusertype Coalition Civilian")
        assertEquals("Coalition Civilian", rec.last?.userType)
        send("b", "#weartaktrackercommandsetheight 5'8\"")
        assertEquals(8, rec.last?.heightInches)
        send("c", "#weartaktrackercommandenrollserver Ops; tak.example.org; 8089; alice; pa;ss")
        assertEquals(PresetServer("Ops", "tak.example.org", 8089, "alice", "pa;ss"), added.single())
        assertTrue(replies.none { it.contains("pa;ss") || it.contains("alice") })
    }

    @Test
    fun presetSummaryNeverShowsCredentials() {
        val json = """{"profile":{"team":"Blue","Callsign":"ODIN"},"servers":[{"name":"A","url":"a.example.org","port":8443,"username":"alice","password":"s3cret"}]}"""
        val s = ProfilePresets.summarize("odin", json)
        assertTrue(s.valid)
        assertEquals(listOf("callsign" to "ODIN", "team" to "Blue"), s.profile)
        assertEquals(listOf("A (a.example.org:8443)"), s.servers)
        assertFalse(s.toString().contains("alice") || s.toString().contains("s3cret"))
        assertFalse(ProfilePresets.summarize("bad", "nope").valid)
    }

    @Test
    fun medicalProfileKeepsPantsAndWaist() {
        val m = MedicalProfileCodec.decode(MedicalProfileCodec.encode(MedicalProfile(pantsLengthInches = 30, waistInches = 32)))
        assertEquals(30, m.pantsLengthInches)
        assertEquals(32, m.waistInches)
        assertEquals(0, MedicalProfileCodec.decode("{}").pantsLengthInches)
    }

    @Test
    fun takProtocolDatagramDecodesToChatEvent() {
        val chat = "<__chat id='ME' senderCallsign='ODIN' messageId='m1'><chatgrp uid0='U1' id='ME'/></__chat>" +
            "<remarks>#weartaktrackercommandsetteam Blue</remarks>"
        val event = field(1, "b-t-f".toByteArray()) + field(5, "GeoChat.U1.ME.m1".toByteArray()) +
            byteArrayOf(0x30) + varint(1_700_000_000_000) + field(15, field(1, chat.toByteArray()))
        val datagram = byteArrayOf(0xbf.toByte(), 1, 0xbf.toByte()) + field(2, event)
        val xml = TakDatagramDecoder.decode(datagram)
        assertNotNull(xml)
        val req = InboundChat.commandRequest(xml!!, "ME", "multicast:224.10.10.1:17012", 0)
        assertEquals("#weartaktrackercommandsetteam Blue", req?.text)
        assertEquals(1_700_000_000_000, req?.sentAtMs)
        assertEquals("<event/>", TakDatagramDecoder.decode("<event/>".toByteArray()))
        assertNull(TakDatagramDecoder.decode(byteArrayOf(0xbf.toByte(), 1, 0xbf.toByte(), 0x12, 0x7f)))
    }

    @Test
    fun meshPliPointsDirectChatAtMeshGroup() {
        val xml = "<contact endpoint='*:-1:stcp' callsign='T'/>"
        assertEquals("<contact endpoint='224.10.10.1:17012:udp' callsign='T'/>", CotBuilder.meshContact(xml))
    }

    private fun varint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (v >= 0x80) { out += ((v and 0x7f) or 0x80).toByte(); v = v ushr 7 }
        out += v.toByte()
        return out.toByteArray()
    }

    private fun field(number: Int, payload: ByteArray) =
        varint(((number shl 3) or 2).toLong()) + varint(payload.size.toLong()) + payload

    @Test
    fun presetParserRejectsBadServers() {
        val good = """{"profile":{"team":"Blue"},"servers":[{"name":"A","url":"tak.example.org","port":8089,"username":"u","password":"p"}]}"""
        assertEquals(1, ProfilePresetParser.parse(good)?.servers?.size)
        assertNull(ProfilePresetParser.parse("""{"servers":[{"name":"A","url":"x y","username":"u","password":"p"}]}"""))
        assertNull(ProfilePresetParser.parse("not json"))
    }

    @Test
    fun loadProfileAppliesIdentityAndServers() {
        val rec = Recorder()
        val json = """{"profile":{"callsign":"ODIN","team":"Blue"},"servers":[{"name":"A","url":"a.example.org","username":"u","password":"p"}]}"""
        var added = 0
        val h = LoadProfileHandler(
            source = { json },
            profile = profile(rec),
            serverSink = { added = it.size; it.size },
            networkLocked = { false },
        )
        val r = runBlocking { h.execute(ChatCommand("loadprofile", mapOf("name" to "odin-weartak")), request) }
        assertTrue(r.message, r.ok)
        assertEquals(1, added)
        assertEquals("ODIN", rec.last?.callsign)
        assertFalse(r.message.contains("password"))
    }

    @Test
    fun loadProfileRefusedWhenNetworkLocked() {
        val rec = Recorder()
        val json = """{"servers":[{"name":"A","url":"a.example.org","username":"u","password":"p"}]}"""
        val h = LoadProfileHandler(
            { json }, ProfileHandler(setOf("team"), rec), { error("must not add") }, { true },
        )
        assertFalse(runBlocking { h.execute(ChatCommand("loadprofile", mapOf("name" to "x")), request) }.ok)
    }

    @Test
    fun framerSplitsAndBoundsStream() {
        val f = CotEventFramer(maxBytes = 100)
        assertTrue(f.feed("<event a='1'><x/></ev").isEmpty())
        assertEquals(listOf("<event a='1'><x/></event>", "<event/></event>"), f.feed("ent><event/></event>"))
        assertTrue(f.feed("z".repeat(200)).isEmpty())
        assertEquals(1, f.feed("<event/></event>").size)
    }

    @Test
    fun builtChatReplyParsesAsDirectMessage() {
        val xml = CotBuilder.chat("ME", "Tracker", "U1", "ODIN", "#weartaktrackercommandsetteam team=Blue", "m9", 1_700_000_000_000)
        val parsed = InboundChat.commandRequest(xml.replace("senderCallsign='Tracker'", "senderCallsign='ODIN'")
            .replace("uid0='ME'", "uid0='U1'").replace("id='U1'", "id='ME'"), "ME", "srv", 5L)
        assertNotNull(parsed)
        assertEquals("ODIN", parsed!!.senderCallsign)
        assertEquals("m9", parsed.messageId)
    }

    @Test
    fun inboundIgnoresOtherRoomsAndDoctype() {
        val groupMsg = "<event><detail><__chat id='All Chat Rooms' senderCallsign='A' messageId='1'><chatgrp uid0='U1' id='All Chat Rooms'/></__chat><remarks>#weartaktrackercommandsetteam team=Blue</remarks></detail></event>"
        assertNull(InboundChat.commandRequest(groupMsg, "ME", "s", 0))
        val xxe = "<!DOCTYPE e [<!ENTITY x 'y'>]><event><__chat/>#weartaktrackercommand</event>"
        assertNull(InboundChat.commandRequest(xxe, "ME", "s", 0))
    }

    private val atakDirectChat =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><event version="2.0" uid="GeoChat.ANDROID-tab.WEAROS_me.abc" type="b-t-f" time="2026-05-04T16:49:05.12Z" start="2026-05-04T16:49:05.12Z" stale="2026-05-05T16:49:05.12Z" how="h-g-i-g-o"><point lat="0" lon="0" hae="9999999" ce="9999999" le="9999999"/><detail><__chat parent="RootContactGroup" groupOwner="false" messageId="abc" chatroom="WT-BBFA" id="ROOM" senderCallsign="ODIN-ATAKDEV"><chatgrp uid0="ANDROID-tab" uid1="WEAROS_me" id="ROOM"/></__chat><link uid="ANDROID-tab" type="a-f-G-U-C" relation="p-p"/><remarks source="BAO.F.ATAK.ANDROID-tab" to="TO" time="2026-05-04T16:49:05.12Z">#weartaktrackercommandloadprofile name=odin-weartaktracker</remarks></detail></event>"""

    @Test
    fun atakDirectChatIsAcceptedWithAnyFractionDigits() {
        val xml = atakDirectChat.replace("ROOM", "WEAROS_me").replace("TO", "WEAROS_me")
        val req = InboundChat.commandRequest(xml, "WEAROS_me", "multicast:224.10.10.1:17012", 0, "WT-BBFA")
        assertNotNull(req)
        assertEquals("ODIN-ATAKDEV", req!!.senderCallsign)
        assertEquals(java.time.Instant.parse("2026-05-04T16:49:05.120Z").toEpochMilli(), req.sentAtMs)
        assertEquals(1_000L, InboundChat.parseTime("1970-01-01T00:00:01Z"))
        assertEquals(1_000L, InboundChat.parseTime("1970-01-01T00:00:01.000000+00:00"))
        assertNull(InboundChat.parseTime("garbage"))
    }

    @Test
    fun directChatInSenderRoomCountsWhenAddressedToMe() {
        val drops = mutableListOf<String>()
        val byParticipant = atakDirectChat.replace("ROOM", "ANDROID-tab").replace("TO", "x")
        assertNotNull(InboundChat.commandRequest(byParticipant, "WEAROS_me", "s", 0, "WT-BBFA"))
        val byCallsign = byParticipant.replace("uid1=\"WEAROS_me\"", "uid1=\"other\"").replace("to=\"x\"", "to=\"wt-bbfa\"")
        assertNotNull(InboundChat.commandRequest(byCallsign, "WEAROS_me", "s", 0, "WT-BBFA"))
        val notMine = byParticipant.replace("uid1=\"WEAROS_me\"", "uid1=\"other\"")
        assertNull(InboundChat.commandRequest(notMine, "WEAROS_me", "s", 0, "WT-BBFA") { drops += it })
        val otherGroup = atakDirectChat.replace("ROOM", "some-group").replace("TO", "WEAROS_me")
        assertNull(InboundChat.commandRequest(otherGroup, "WEAROS_me", "s", 0, "WT-BBFA") { drops += it })
        assertEquals(2, drops.size)
        assertTrue(drops.none { it.contains("odin-weartaktracker") })
    }

    @Test
    fun protobufAtakDirectChatDecodesWithPointFields() {
        val detail = "<__chat id='WEAROS_me' senderCallsign='ODIN-ATAKDEV' messageId='m2'><chatgrp uid0='ANDROID-tab' uid1='WEAROS_me' id='WEAROS_me'/></__chat>" +
            "<remarks to='WEAROS_me'>#weartaktrackercommandsetteam Blue</remarks>"
        val fixed64 = { f: Int -> byteArrayOf(((f shl 3) or 1).toByte()) + ByteArray(8) }
        val event = field(1, "b-t-f".toByteArray()) + field(5, "GeoChat.x".toByteArray()) +
            byteArrayOf(0x30) + varint(1_700_000_000_000) + byteArrayOf(0x38) + varint(1_700_000_000_000) +
            field(9, "h-g-i-g-o".toByteArray()) + fixed64(10) + fixed64(11) + fixed64(12) + fixed64(13) + fixed64(14) +
            field(15, field(1, detail.toByteArray()))
        val xml = TakDatagramDecoder.decode(byteArrayOf(0xbf.toByte(), 1, 0xbf.toByte()) + field(2, event))
        val req = InboundChat.commandRequest(xml!!, "WEAROS_me", "multicast:224.10.10.1:17012", 0, "WT-BBFA")
        assertEquals(1_700_000_000_000, req?.sentAtMs)
        assertEquals("<e/>", TakDatagramDecoder.decode("\uFEFF  <e/>".toByteArray()))
    }

    @Test
    fun staleCommandFromAllowedSenderGetsReplyButUnknownSenderDoesNot() {
        val replies = mutableListOf<String>()
        val p = ChatCommandProcessor(
            isEnabled = { true },
            allowedCallsigns = { listOf("ODIN") },
            handlers = emptyMap(),
            reply = { _, m -> replies += m },
            audit = {},
            now = { 10 * 60_000L },
        )
        runBlocking {
            p.handle(ChatCommandRequest("U1", "ODIN", "#weartaktrackercommandsetteam Blue", "a", 0, 0, "s"))
            p.handle(ChatCommandRequest("U2", "EVE", "#weartaktrackercommandsetteam Blue", "b", 0, 0, "s"))
        }
        assertEquals(1, replies.size)
        assertTrue(replies[0].contains("clock"))
    }
}
