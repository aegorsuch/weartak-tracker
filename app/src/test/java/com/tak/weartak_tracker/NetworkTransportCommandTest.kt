package com.tak.weartak_tracker

import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandParse
import com.tak.weartak_tracker.command.ChatCommandParser
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.NetworkCommandApplyResult
import com.tak.weartak_tracker.command.NetworkCommandTransport
import com.tak.weartak_tracker.command.NetworkTransportCommandHandler
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.TrackerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetworkTransportCommandTest {
    private val now = 1_800_000_000_000L
    private val takRequest = ChatCommandRequest(
        "admin", "ADMIN", "#weartakcommanddisabletakservers", "message", now, now, "ops-server",
    )

    private fun command(text: String): ChatCommand {
        val parsed = ChatCommandParser.parse(text)
        assertTrue(parsed is ChatCommandParse.Parsed)
        return (parsed as ChatCommandParse.Parsed).command
    }

    @Test
    fun disablingTheOriginatingTransportWritesWarningBeforeChangingOnlyItsMasterSwitch() = runBlocking {
        data class DisableCase(
            val transport: NetworkCommandTransport,
            val request: ChatCommandRequest,
            val expectedMessage: String,
        )
        for ((transport, request, expectedMessage) in listOf(
            DisableCase(
                NetworkCommandTransport.TAK_SERVERS, takRequest,
                "TAK Servers off requested; remote delivery unconfirmed",
            ),
            DisableCase(
                NetworkCommandTransport.MULTICAST,
                takRequest.copy(sourceServerId = "multicast:224.10.10.1:17012", text = "#weartakcommanddisablemulticast"),
                "Multicast off requested; remote delivery unconfirmed",
            ),
        )) {
            var config = TrackerConfig(servers = listOf(
                TakServerConfig(id = "one", enabled = true),
                TakServerConfig(id = "two", enabled = false),
            ))
            val events = mutableListOf<String>()
            val handler = NetworkTransportCommandHandler(
                transport = transport,
                enabled = false,
                networkLocked = { false },
                store = { selected, state ->
                    events += "store"
                    config = when (selected) {
                        NetworkCommandTransport.TAK_SERVERS -> config.copy(takServersEnabled = state)
                        NetworkCommandTransport.MULTICAST -> config.copy(multicastEnabled = state)
                    }
                    NetworkCommandApplyResult(true)
                },
                beforeDisableReply = { _, message ->
                    assertTrue(message.startsWith(expectedMessage))
                    events += "warning"
                    true
                },
                ackFailure = { fail("Successful warning must not report failure") },
            )

            val result = handler.execute(command(request.text), request)
            assertTrue(result.ok)
            assertTrue(result.replyAttempted)
            assertEquals(listOf("warning", "store"), events)
            assertEquals(listOf(true, false), config.servers.map { it.enabled })
            assertEquals(transport != NetworkCommandTransport.TAK_SERVERS, config.takServersEnabled)
            assertEquals(transport != NetworkCommandTransport.MULTICAST, config.multicastEnabled)
        }
    }

    @Test
    fun failedOriginatingRouteWriteRefusesDisableWithoutChangingSettings() = runBlocking {
        var writes = 0
        var ackFailure = false
        var config = TrackerConfig()
        val handler = NetworkTransportCommandHandler(
            transport = NetworkCommandTransport.TAK_SERVERS,
            enabled = false,
            networkLocked = { false },
            store = { _, enabled -> config = config.copy(takServersEnabled = enabled); NetworkCommandApplyResult(true) },
            beforeDisableReply = { _, _ -> writes++; false },
            ackFailure = { ackFailure = true },
        )

        val result = handler.execute(command(takRequest.text), takRequest)
        assertFalse(result.ok)
        assertTrue(result.replyAttempted)
        assertTrue(result.message.contains("unable to write"))
        assertEquals(1, writes)
        assertTrue(ackFailure)
        assertTrue(config.takServersEnabled)
    }

    @Test
    fun refusesLockedSettingsBeforeWarningAndReportsLateLockAfterWarning() = runBlocking {
        var config = TrackerConfig(networkPreferencesLocked = true)
        var warnings = 0
        var storeCalls = 0
        val locked = NetworkTransportCommandHandler(
            NetworkCommandTransport.TAK_SERVERS, false, { true },
            { _, _ -> storeCalls++; NetworkCommandApplyResult(true) },
            { _, _ -> warnings++; true },
            { fail("No warning is sent when already locked") },
        )
        val result = locked.execute(command(takRequest.text), takRequest)
        assertFalse(result.ok)
        assertTrue(result.message.contains("Network settings are locked"))
        assertEquals(0, warnings)
        assertEquals(0, storeCalls)
        assertTrue(config.takServersEnabled)

        config = TrackerConfig()
        val becameLocked = NetworkTransportCommandHandler(
            NetworkCommandTransport.TAK_SERVERS, false, { false },
            { _, _ -> storeCalls++; NetworkCommandApplyResult(applied = false, locked = true) },
            { _, _ -> warnings++; true },
            { },
        )
        val lateResult = becameLocked.execute(command(takRequest.text), takRequest)
        assertFalse(lateResult.ok)
        assertFalse(lateResult.replyAttempted)
        assertEquals(1, warnings)
        assertEquals(1, storeCalls)
        assertTrue(config.takServersEnabled)
    }

    @Test
    fun commandsRequireNoArgumentsAndOtherOriginRoutesUseNormalPostChangeReply() = runBlocking {
        var changed = false
        var preReplies = 0
        val handler = NetworkTransportCommandHandler(
            NetworkCommandTransport.MULTICAST, false, { false },
            { _, enabled -> changed = enabled; NetworkCommandApplyResult(true) },
            { _, _ -> preReplies++; true },
            { },
        )
        val invalid = handler.execute(
            command("#weartakcommanddisablemulticast extra=true"),
            takRequest.copy(text = "#weartakcommanddisablemulticast extra=true"),
        )
        assertFalse(invalid.ok)
        assertFalse(changed)
        assertEquals(0, preReplies)

        val result = handler.execute(
            command("#weartakcommanddisablemulticast"),
            takRequest.copy(text = "#weartakcommanddisablemulticast"),
        )
        assertTrue(result.ok)
        assertFalse(result.replyAttempted)
        assertFalse(changed)
        assertEquals(0, preReplies)
    }

    @Test
    fun enableCommandsChangeOnlyTheirPersistedMasterAndUseTheNormalReplyPath() = runBlocking {
        for ((text, transport) in listOf(
            "#weartakcommand enabletakservers" to NetworkCommandTransport.TAK_SERVERS,
            "#weartakcommandenablemulticast" to NetworkCommandTransport.MULTICAST,
        )) {
            var config = TrackerConfig(
                takServersEnabled = false,
                multicastEnabled = false,
                servers = listOf(TakServerConfig(id = "saved", enabled = false)),
            )
            var preReplies = 0
            val handler = NetworkTransportCommandHandler(
                transport = transport,
                enabled = true,
                networkLocked = { false },
                store = { selected, enabled ->
                    config = when (selected) {
                        NetworkCommandTransport.TAK_SERVERS -> config.copy(takServersEnabled = enabled)
                        NetworkCommandTransport.MULTICAST -> config.copy(multicastEnabled = enabled)
                    }
                    NetworkCommandApplyResult(true)
                },
                beforeDisableReply = { _, _ -> preReplies++; true },
                ackFailure = { fail("Enable command must not pre-ack") },
            )
            val request = takRequest.copy(text = text)
            val result = handler.execute(command(text), request)
            assertTrue(result.ok)
            assertFalse(result.replyAttempted)
            assertEquals(0, preReplies)
            assertEquals(transport == NetworkCommandTransport.TAK_SERVERS, config.takServersEnabled)
            assertEquals(transport == NetworkCommandTransport.MULTICAST, config.multicastEnabled)
            assertEquals(listOf(false), config.servers.map { it.enabled })
        }
    }
}
