package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.TakChannel
import com.tak.weartak_tracker.data.TakChannelState
import com.tak.weartak_tracker.data.TakChannelStatus
import com.tak.weartak_tracker.data.TakServerChannels
import com.tak.weartak_tracker.data.ServerListCodec
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.transport.TakChannelJson
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelsTest {
    private val groups = """
        {"version":"3","type":"com.bbn.marti.remote.groups.Group","data":[
          {"name":"Blue","direction":"IN","created":"2024-01-01","type":"SYSTEM","bitpos":2,"active":true,"description":"x"},
          {"name":"Blue","direction":"OUT","created":"2024-01-01","type":"SYSTEM","bitpos":2,"active":true},
          {"name":"Red","direction":"IN","bitpos":3,"active":false},
          {"name":"","direction":"IN","bitpos":4,"active":true},
          {"name":"NoBit","direction":"IN","active":true}
        ],"nodeId":"n1"}
    """.trimIndent()

    @Test
    fun parsesSupportAndGroups() {
        assertTrue(TakChannelJson.parseSupport("""{"data":true}"""))
        assertFalse(TakChannelJson.parseSupport("""{"data":false}"""))
        assertTrue(runCatching { TakChannelJson.parseSupport("""{"data":"yes"}""") }.exceptionOrNull() is JSONException)

        val snapshot = TakChannelJson.parseGroups(groups)
        assertEquals(5, snapshot.payload.length())
        assertEquals(
            listOf(TakChannel("Blue", "IN", true, 2), TakChannel("Red", "IN", false, 3)),
            snapshot.channels,
        )
        assertTrue(runCatching { TakChannelJson.parseGroups("""{"data":{}}""") }.exceptionOrNull() is JSONException)
        assertTrue(runCatching { TakChannelJson.parseGroups("<html>") }.exceptionOrNull() is JSONException)
    }

    @Test
    fun toggleUpdatesEveryDirectionAndKeepsOtherFields() {
        val original = TakChannelJson.parseGroups(groups).payload
        val updated = TakChannelJson.withActive(original, 2, false)
        assertFalse(updated.payload.getJSONObject(0).getBoolean("active"))
        assertFalse(updated.payload.getJSONObject(1).getBoolean("active"))
        assertEquals("x", updated.payload.getJSONObject(0).getString("description"))
        assertEquals("SYSTEM", updated.payload.getJSONObject(1).getString("type"))
        // The source payload is not mutated.
        assertTrue(original.getJSONObject(0).getBoolean("active"))
        assertFalse(updated.channels.first { it.bitPosition == 2 }.active)
        assertTrue(runCatching { TakChannelJson.withActive(original, 99, true) }.exceptionOrNull() is JSONException)
    }

    @Test
    fun optimisticStateTransitions() {
        val ready = TakChannelState.loaded("s1", listOf(TakChannel("Blue", "IN", true, 2), TakChannel("Red", "IN", false, 3)))
        assertEquals(TakChannelStatus.READY, ready.status)
        assertEquals(TakChannelStatus.EMPTY, TakChannelState.loaded("s1", emptyList()).status)

        val pending = TakChannelState.beginUpdate(ready, 3, true)
        assertEquals(TakChannel("Red", "IN", true, 3, updating = true), pending.channels[1])
        assertEquals(ready.channels[0], pending.channels[0])

        val failed = TakChannelState.updateFailed(ready, "boom")
        assertEquals(TakChannelStatus.ERROR, failed.status)
        assertEquals("boom", failed.errorMessage)
        assertEquals(ready.channels, failed.channels)

        val loading = TakChannelState.loading("s1", pending)
        assertEquals(TakChannelStatus.LOADING, loading.status)
        val loadFailed = TakChannelState.loadFailed("s1", pending, "offline")
        assertTrue(loadFailed.channels.none(TakChannel::updating))
        assertEquals(TakServerChannels("s1", TakChannelStatus.LOADING), TakChannelState.loading("s1", null))
    }

    @Test
    fun discoveredTlsNamesPersistButDontForceReconnect() {
        val server = TakServerConfig(id = "a", name = "n", address = "10.0.0.1", tlsName = "tak.example.com", apiTlsName = "api.example.com")
        assertEquals(listOf(server), ServerListCodec.decode(ServerListCodec.encode(listOf(server))))
        assertEquals(server.copy(tlsName = "").connectionKey, server.connectionKey)
        assertFalse(server.copy(port = 1).connectionKey == server.connectionKey)
    }
}
