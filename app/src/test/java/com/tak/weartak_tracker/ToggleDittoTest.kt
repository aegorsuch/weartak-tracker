package com.tak.weartak_tracker

import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandParse
import com.tak.weartak_tracker.command.ChatCommandParser
import com.tak.weartak_tracker.command.ChatCommandProcessor
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.DittoPdlApplyResult
import com.tak.weartak_tracker.command.DittoToggleStore
import com.tak.weartak_tracker.command.ToggleDittoHandler
import com.tak.weartak_tracker.data.DittoPdlSource
import com.tak.weartak_tracker.data.TrackerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToggleDittoTest {
    private val now = 1_800_000_000_000L
    private val request = ChatCommandRequest("admin", "ADMIN", "#weartakcommandtoggleditto off",
        "message", now, now, "ditto:chat", "All Chat Rooms", "All Chat Rooms")

    @Test
    fun parserRequiresExactlyOneExplicitState() {
        for (state in listOf("on", "off", "ON", "OFF")) {
            val command = ChatCommandParser.parse("#weartakcommandtoggleditto $state") as ChatCommandParse.Parsed
            assertEquals(state.lowercase(), command.command.args["state"])
        }
        for (arg in listOf("", "true", "false", "toggle", "on off", "enabled=true", "state=on", "on server=ops")) {
            assertTrue(ChatCommandParser.parse("#weartakcommandtoggleditto $arg") is ChatCommandParse.Malformed)
        }
    }

    @Test
    fun dittoOffAttemptsRoomAckBeforePersistentDisableAndSuppressesSecondAck() = runBlocking {
        val events = mutableListOf<String>()
        val handler = ToggleDittoHandler({ false }, DittoToggleStore {
            assertFalse(it)
            events += "persist-and-apply-off"
            DittoPdlApplyResult(true)
        }, { req, message ->
            assertEquals("All Chat Rooms", req.replyRoomId)
            assertTrue(message.contains("requested"))
            assertTrue(message.contains("remote delivery unconfirmed"))
            events += "pre-ack"
        }, { fail("Unexpected ack failure") })
        val processor = ChatCommandProcessor({ true }, { listOf("ADMIN") }, mapOf("toggleditto" to handler),
            { _, _ -> events += "late-ack" }, {}, { now })
        processor.handle(request)
        assertEquals(listOf("pre-ack", "persist-and-apply-off"), events)
        processor.handle(request.copy(sourceServerId = "server-ops"))
        assertEquals(2, events.size)
    }

    @Test
    fun repeatedExplicitStatesPreserveIdentityAndAreNotBlindToggles() = runBlocking {
        var config = TrackerConfig(dittoEnabled = true, dittoPdlSource = DittoPdlSource.CUSTOM,
            dittoCustomPdl = "synthetic-identity")
        val identity = config.dittoCustomPdl
        val handler = ToggleDittoHandler({ false }, DittoToggleStore {
            config = config.copy(dittoEnabled = it)
            DittoPdlApplyResult(true)
        }, { _, _ -> fail("Non-Ditto route must ack after apply") }, {})
        for (state in listOf("off", "off", "on", "on")) {
            val result = handler.execute(ChatCommand("toggleditto", mapOf("state" to state)),
                request.copy(sourceServerId = "multicast:224.10.10.1:17012"))
            assertTrue(result.ok)
            assertFalse(result.replyAttempted)
            assertEquals(state == "on", config.dittoEnabled)
            assertEquals(identity, config.dittoCustomPdl)
            assertEquals(DittoPdlSource.CUSTOM, config.dittoPdlSource)
        }
    }

    @Test
    fun unavailableAckDoesNotPretendRemoteDeliveryOrPreventRequestedOff() = runBlocking {
        var ackFailed = false
        var disabled = false
        val handler = ToggleDittoHandler({ false }, DittoToggleStore {
            disabled = !it; DittoPdlApplyResult(true)
        }, { _, _ -> throw IllegalStateException("synthetic publication failure") }, { ackFailed = true })
        val result = handler.execute(ChatCommand("toggleditto", mapOf("state" to "off")), request)
        assertTrue(result.ok)
        assertTrue(result.replyAttempted)
        assertTrue(ackFailed)
        assertTrue(disabled)
    }

    @Test
    fun dittoCannotReEnableItselfFromQueuedCommandsAfterOffButAnotherRouteCan() = runBlocking {
        var enabled = true
        val handler = ToggleDittoHandler({ false }, DittoToggleStore {
            enabled = it; DittoPdlApplyResult(true)
        }, { _, _ -> }, {})
        val processor = ChatCommandProcessor({ true }, { listOf("ADMIN") }, mapOf("toggleditto" to handler),
            { _, _ -> }, {}, { now }, isRouteEnabled = { it.sourceServerId != "ditto:chat" || enabled })
        processor.handle(request)
        assertFalse(enabled)
        val on = request.copy(text = "#weartakcommandtoggleditto on", messageId = "on")
        processor.handle(on)
        assertFalse(enabled)
        processor.handle(on.copy(sourceServerId = "server-ops"))
        assertTrue(enabled)
    }

    @Test
    fun invalidStateAndLockedSettingsDoNotAckOrMutateAndRuntimeFailuresAreReported() = runBlocking {
        val locked = ToggleDittoHandler({ true }, DittoToggleStore { fail("Locked"); DittoPdlApplyResult(false) },
            { _, _ -> fail("Locked must not pre-ack off") }, {})
        assertFalse(locked.execute(ChatCommand("toggleditto", mapOf("state" to "off")), request).ok)
        val handler = ToggleDittoHandler({ false }, DittoToggleStore { DittoPdlApplyResult(false) },
            { _, _ -> }, {})
        assertFalse(handler.execute(ChatCommand("toggleditto", mapOf("state" to "true")), request).ok)
        val failed = handler.execute(ChatCommand("toggleditto", mapOf("state" to "on")), request)
        assertEquals("Ditto update failed", failed.message)
        assertFalse(failed.ok)
    }
}
