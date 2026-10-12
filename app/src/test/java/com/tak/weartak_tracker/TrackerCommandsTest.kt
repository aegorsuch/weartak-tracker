package com.tak.weartak_tracker

import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.IdentityHandler
import com.tak.weartak_tracker.command.IdentityStore
import com.tak.weartak_tracker.command.LoadProfileHandler
import com.tak.weartak_tracker.command.ProfilePresetParser
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotEventFramer
import com.tak.weartak_tracker.cot.InboundChat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerCommandsTest {
    private val request = ChatCommandRequest("U1", "ODIN", "x", "m1", 0, 0, "s")

    private class Recorder : IdentityStore {
        var last: Triple<String?, String?, String?>? = null
        override suspend fun apply(callsign: String?, team: String?, role: String?) {
            last = Triple(callsign, team, role)
        }
    }

    private fun run(h: IdentityHandler, name: String, vararg args: Pair<String, String>) =
        runBlocking { h.execute(ChatCommand(name, args.toMap()), request) }

    @Test
    fun identityValidatesBeforeStoring() {
        val rec = Recorder()
        val h = IdentityHandler(IdentityHandler.COMMAND_KEYS.getValue("setprofile"), rec)
        assertFalse(run(h, "setprofile", "team" to "Dark Green", "role" to "Nope").ok)
        assertNull(rec.last)
        assertTrue(run(h, "setprofile", "team" to "dark green", "role" to "K9").ok)
        assertEquals(Triple(null, "Dark Green", "K9"), rec.last)
    }

    @Test
    fun identityRejectsUnknownKeys() {
        val h = IdentityHandler(setOf("team"), Recorder())
        assertFalse(run(h, "setteam", "role" to "K9").ok)
    }

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
            identity = IdentityHandler(IdentityHandler.COMMAND_KEYS.getValue("setprofile"), rec),
            serverSink = { added = it.size; it.size },
            networkLocked = { false },
        )
        val r = runBlocking { h.execute(ChatCommand("loadprofile", mapOf("name" to "odin-weartak")), request) }
        assertTrue(r.message, r.ok)
        assertEquals(1, added)
        assertEquals("ODIN", rec.last?.first)
        assertFalse(r.message.contains("password"))
    }

    @Test
    fun loadProfileRefusedWhenNetworkLocked() {
        val rec = Recorder()
        val json = """{"servers":[{"name":"A","url":"a.example.org","username":"u","password":"p"}]}"""
        val h = LoadProfileHandler(
            { json }, IdentityHandler(setOf("team"), rec), { error("must not add") }, { true },
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
}
