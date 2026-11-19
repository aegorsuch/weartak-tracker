package com.tak.weartak_tracker

import androidx.compose.runtime.mutableStateOf
import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.DittoPdlApplyResult
import com.tak.weartak_tracker.command.SetPdlHandler
import com.tak.weartak_tracker.cot.Fix
import com.tak.weartak_tracker.data.DittoPdlSource
import com.tak.weartak_tracker.data.DittoState
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.service.DittoClient
import com.tak.weartak_tracker.service.DittoPeerSession
import com.tak.weartak_tracker.ui.DITTO_PLUGIN_NAME
import com.tak.weartak_tracker.ui.PLUGIN_TITLES
import com.tak.weartak_tracker.ui.dittoToggleCallback
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DittoClientTest {
    private val community = pdl("community")
    private val enabled = TrackerConfig(dittoEnabled = true, callsign = "ALPHA")
    private val sessions = mutableListOf<FakeSession>()
    private val errors = mutableListOf<String>()
    private var ready = 0
    private val client = DittoClient(
        createSession = { FakeSession().also(sessions::add) },
        communityIdentity = { community },
        now = { 1_000L },
        logError = { message, _ -> errors.add(message) },
        onReady = { ready++ },
    )

    @Test
    fun retainedToggleCallbackUsesLiveSettingForOffOnOff() {
        val config = mutableStateOf(enabled)
        val callback = dittoToggleCallback(config) { on ->
            config.value = config.value.copy(dittoEnabled = on)
            client.configure(config.value)
        }
        client.configure(config.value)
        callback(false)
        assertEquals(false, config.value.dittoEnabled)
        // Simulate a retained control still deriving its request from the original checked=true.
        callback(false)
        assertEquals(true, config.value.dittoEnabled)
        assertEquals(2, sessions.single().starts)
        callback(false)
        assertEquals(false, config.value.dittoEnabled)
        assertEquals(2, sessions.single().pauses)
    }

    @Test
    fun repeatedOffOnCyclesResumeWithoutClosingOrReopeningTheDatabase() {
        assertEquals(DittoState.Disabled, client.configure(enabled.copy(dittoEnabled = false)))
        assertTrue(sessions.isEmpty())
        assertEquals(DittoState.Active, client.configure(enabled))
        val session = sessions.single()
        repeat(3) {
            assertEquals(DittoState.Disabled, client.configure(enabled.copy(dittoEnabled = false)))
            assertEquals(DittoState.Disabled, client.configure(enabled.copy(dittoEnabled = false)))
            assertEquals(DittoState.Active, client.configure(enabled))
            assertEquals(DittoState.Active, client.configure(enabled))
        }
        assertEquals(1, sessions.size)
        assertEquals(4, session.starts)
        assertEquals(4, ready)
        assertEquals(4L, client.readyGeneration)
        assertEquals(3, session.pauses)
        assertEquals(0, session.closes)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun pausedSessionDoesNotPublishAndResumedSessionDoes() {
        client.configure(enabled)
        publish()
        client.configure(enabled.copy(dittoEnabled = false))
        assertEquals(null, client.readyGeneration)
        publish()
        assertEquals(1, sessions.single().documents.size)
        client.configure(enabled)
        publish()
        assertEquals(2, sessions.single().documents.size)
    }

    @Test
    fun resumeUsesCurrentCallsignWithoutRestartingActiveSync() {
        client.configure(enabled)
        client.configure(enabled.copy(dittoEnabled = false))
        client.configure(enabled.copy(callsign = "BRAVO"))
        assertEquals("BRAVO", sessions.single().deviceName)
        client.configure(enabled.copy(callsign = "CHARLIE"))
        assertEquals("CHARLIE", sessions.single().deviceName)
        assertEquals(2, sessions.single().starts)
    }

    @Test
    fun changingIdentityWhilePausedClosesOldDatabaseBeforeCreatingNewOne() {
        client.configure(enabled)
        val old = sessions.single()
        client.configure(enabled.copy(dittoEnabled = false))
        assertEquals(DittoState.Active, client.configure(
            enabled.copy(dittoPdlSource = DittoPdlSource.CUSTOM, dittoCustomPdl = pdl("custom")),
        ))
        assertEquals(1, old.closes)
        assertEquals(2, sessions.size)
        assertEquals(1, sessions.last().starts)
    }

    @Test
    fun changingIdentityWhileDisabledReleasesOldSessionImmediately() {
        client.configure(enabled)
        client.configure(enabled.copy(
            dittoEnabled = false, dittoPdlSource = DittoPdlSource.CUSTOM, dittoCustomPdl = pdl("custom"),
        ))
        assertEquals(1, sessions.single().closes)
        assertEquals(1, sessions.size)
    }

    @Test
    fun commandChangesRunningIdentityAndPreservesToggleState() = runBlocking {
        var config = enabled
        client.configure(config)
        val old = sessions.single()
        val handler = SetPdlHandler(
            communityIdentity = { community },
            store = { update ->
                config = config.copy(
                    dittoPdlSource = update.source,
                    dittoCustomPdl = update.customPdl ?: config.dittoCustomPdl,
                )
                val state = client.configure(config)
                DittoPdlApplyResult(state == DittoState.Active || state == DittoState.Disabled)
            },
            networkLocked = { false }, now = { 1_000L },
        )
        val request = ChatCommandRequest("sender", "ODIN", "", "m1", 1_000L, 1_000L, "s1")
        assertTrue(handler.execute(ChatCommand("setpdl", mapOf("pdl" to pdl("custom"))), request).ok)
        assertEquals(1, old.closes)
        assertEquals(2, sessions.size)
        config = config.copy(dittoEnabled = false)
        client.configure(config)
        assertTrue(handler.execute(ChatCommand("setpdl", mapOf("pdl" to "community")), request).ok)
        assertEquals(false, config.dittoEnabled)
        assertEquals(1, sessions.last().closes)
        assertEquals(2, sessions.size)
        config = config.copy(dittoEnabled = true)
        assertEquals(DittoState.Active, client.configure(config))
        assertEquals(3, sessions.size)
    }

    @Test
    fun shutdownClosesPausedSessionOnceAndAllowsFreshStart() {
        client.configure(enabled)
        client.configure(enabled.copy(dittoEnabled = false))
        client.stop()
        client.stop()
        assertEquals(1, sessions.single().closes)
        assertEquals(DittoState.Active, client.configure(enabled))
        assertEquals(2, sessions.size)
    }

    @Test
    fun invalidOrExpiredIdentityDoesNotStartSync() {
        client.configure(enabled)
        client.configure(enabled.copy(dittoEnabled = false))
        val custom = enabled.copy(dittoPdlSource = DittoPdlSource.CUSTOM)
        assertEquals(DittoState.NeedsIdentity, client.configure(custom))
        assertEquals(1, sessions.single().closes)
        assertTrue(client.configure(custom.copy(dittoCustomPdl = "invalid")) is DittoState.Error)
        assertTrue(client.configure(custom.copy(dittoCustomPdl = pdl("expired", 999L))) is DittoState.Error)
        assertEquals(1, sessions.size)
        assertEquals(1, ready)
    }

    @Test
    fun failedResumeReportsErrorAndNextEnableCreatesFreshSession() {
        client.configure(enabled)
        client.configure(enabled.copy(dittoEnabled = false))
        sessions.single().failStart = true
        assertTrue(client.configure(enabled) is DittoState.Error)
        assertEquals(1, ready)
        assertEquals(1, sessions.single().closes)
        assertEquals(1, errors.size)
        assertEquals(DittoState.Active, client.configure(enabled))
        assertEquals(2, sessions.size)
        assertEquals(2, ready)
    }

    @Test
    fun readyCallbackCanPublishCurrentSelfTrackOnlyAfterSyncStarts() {
        val session = FakeSession()
        lateinit var readyClient: DittoClient
        readyClient = DittoClient(
            createSession = { session }, communityIdentity = { community }, now = { 1_000L },
            onReady = {
                assertEquals(1, session.starts)
                readyClient.publishTrack(
                    "<event><detail><remarks>MOPP2</remarks></detail></event>",
                    Fix(1.0, 2.0, 0.0, 1f, 1f, 0f, 0f, 1_000L), enabled, "self",
                )
            },
        )
        readyClient.configure(enabled)
        readyClient.configure(enabled)
        assertEquals(1, session.documents.size)
        assertEquals("self", session.documents.single()["_id"])
        assertEquals(1_000L, session.documents.single()["n"])
        assertTrue(session.chats.isEmpty())
    }

    @Test
    fun invalidSdkConfigurationReturnsRedactedErrorWithoutKillingSettingsUpdates() {
        var fail = true
        val client = DittoClient(
            createSession = {
                if (fail) throw IllegalArgumentException("secret-key-value")
                FakeSession()
            },
            communityIdentity = { community },
            now = { 1_000L },
            logError = { _, _ -> },
        )
        assertEquals(DittoState.Error("Invalid Ditto Edge Sync configuration"), client.configure(enabled))
        fail = false
        assertEquals(DittoState.Active, client.configure(enabled))
    }

    @Test
    fun pluginNameAndAlphabeticalPositionMatchWearTak() {
        assertEquals("Ditto Edge Sync", DITTO_PLUGIN_NAME)
        assertEquals(listOf(
            "Commands", "Ditto Edge Sync", "Everywhere Comms", "GoTenna", "MOPP", "Sensor Manager", "Somewear Labs",
        ), PLUGIN_TITLES)
    }

    @Test
    fun explicitChatStatesUseTheExistingSdkPauseResumeSessionIdempotently() = runBlocking {
        var config = enabled
        client.configure(config)
        val handler = com.tak.weartak_tracker.command.ToggleDittoHandler(
            networkLocked = { false },
            store = com.tak.weartak_tracker.command.DittoToggleStore { on ->
                config = config.copy(dittoEnabled = on)
                val state = client.configure(config)
                DittoPdlApplyResult(state == DittoState.Active || state == DittoState.Disabled)
            },
            beforeDisableReply = { _, _ -> throw AssertionError("Server route") },
            ackFailure = {},
        )
        val request = com.tak.weartak_tracker.command.ChatCommandRequest("admin", "ADMIN", "", "id", 1, 1, "server")
        for (state in listOf("off", "off", "on", "on")) {
            assertTrue(handler.execute(ChatCommand("toggleditto", mapOf("state" to state)), request).ok)
        }
        assertEquals(1, sessions.size)
        assertEquals(1, sessions.single().pauses)
        assertEquals(2, sessions.single().starts)
        assertEquals(0, sessions.single().closes)
        assertEquals(enabled.dittoPdlSource, config.dittoPdlSource)
        assertEquals(enabled.dittoCustomPdl, config.dittoCustomPdl)
    }

    @Test
    fun chatObserverIsWiredAndNativeRepliesUseChatOnlyWhileSyncing() {
        val received = mutableListOf<String>()
        val session = FakeSession()
        val chatClient = DittoClient(
            createSession = { session }, communityIdentity = { community }, now = { 1_000 },
            onChat = { raw, peer -> received += "${raw["_id"]}:$peer" },
        )
        chatClient.configure(enabled)
        session.chatObserver!!.invoke(mapOf("_id" to "message"))
        assertEquals(listOf("message:test-peer"), received)
        val xml = com.tak.weartak_tracker.cot.CotBuilder.chat("watch", "WATCH", "ops", "Operations", "Updated")
        assertEquals(1, chatClient.replyChat(xml))
        assertEquals("ops", session.chats.single()["roomId"])
        chatClient.configure(enabled.copy(dittoEnabled = false))
        assertEquals(0, chatClient.replyChat(xml))
        chatClient.configure(enabled)
        assertEquals(1, chatClient.replyChat(xml))
        assertEquals(2, session.chats.size)
    }

    private fun publish() = client.publishTrack(
        "<event><detail/></event>", Fix(1.0, 2.0, 0.0, 1f, 1f, 0f, 0f, 1_000L), enabled, "u1",
    )

    private class FakeSession : DittoPeerSession {
        override var deviceName = ""
        override val peerKey = "test-peer"
        var starts = 0
        var pauses = 0
        var closes = 0
        var failStart = false
        val documents = mutableListOf<Map<String, Any?>>()
        val chats = mutableListOf<Map<String, Any?>>()
        var chatObserver: ((Map<String, Any?>) -> Unit)? = null
        override fun startSync() {
            check(closes == 0)
            if (failStart) throw SecurityException("permissions revoked")
            starts++
        }
        override fun stopSync() { pauses++ }
        override fun publish(document: Map<String, Any?>) { documents.add(document) }
        override fun observeChat(onChat: (Map<String, Any?>) -> Unit) { chatObserver = onChat }
        override fun publishChat(document: Map<String, Any?>) { chats.add(document) }
        override fun close() { closes++ }
    }

    private fun pdl(app: String, expiry: Long = 4_000_000_000_000L): String {
        val json = """{"v":2,"it":"SK","a":"$app","s":"test-key","t":"test-token","e":$expiry}"""
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(json.toByteArray()) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }
}
