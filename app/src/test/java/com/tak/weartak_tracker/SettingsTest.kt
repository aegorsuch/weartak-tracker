package com.tak.weartak_tracker

import com.google.android.gms.location.Priority
import com.tak.weartak_tracker.data.LocationAccess
import com.tak.weartak_tracker.data.ServerListCodec
import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.data.takServerFormError
import com.tak.weartak_tracker.transport.SitxClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun serverListRoundTrips() {
        val servers = listOf(
            TakServerConfig(id = "a", name = "Main \"TAK\"", address = " tak.example.com ", port = 8089, username = "u", password = "p@ss,'<>"),
            TakServerConfig(id = "b", name = "Cert only", address = "10.0.0.5", port = 8090, enabled = false),
        )
        val decoded = ServerListCodec.decode(ServerListCodec.encode(servers))
        assertEquals(servers.map { it.copy(address = it.address.trim()) }, decoded)
    }

    @Test
    fun serverListToleratesMissingFieldsAndGarbage() {
        val decoded = ServerListCodec.decode("""[{"address":"tak"}]""").single()
        assertEquals("tak", decoded.address)
        assertEquals(8089, decoded.port)
        assertTrue(decoded.enabled)
        assertTrue(decoded.id.isNotBlank())
        assertEquals(emptyList<TakServerConfig>(), ServerListCodec.decode("not json"))
        assertEquals(emptyList<TakServerConfig>(), ServerListCodec.decode(null))
    }

    @Test
    fun serverListDropsRepeatedIdsKeepingLatest() {
        val raw = """[{"id":"a","name":"one"},{"id":"b","name":"two"},{"id":"a","name":"one-edited"}]"""
        val decoded = ServerListCodec.decode(raw)
        assertEquals(listOf("b" to "two", "a" to "one-edited"), decoded.map { it.id to it.name })
    }

    @Test
    fun serverFormAllowsCertificateOnlySetup() {
        assertNull(takServerFormError("TAK", "tak", "8089", "", "", hasSideloadedCert = true))
        assertNull(takServerFormError("TAK", "tak", "8089", "user", "pw", hasSideloadedCert = false))
        assertNotNull(takServerFormError("TAK", "tak", "8089", "", "", hasSideloadedCert = false))
        assertNotNull(takServerFormError("TAK", "tak", "8089", "user", "", hasSideloadedCert = true))
        assertNotNull(takServerFormError("TAK", "tak", "70000", "user", "pw", hasSideloadedCert = false))
        assertNotNull(takServerFormError("", "tak", "8089", "user", "pw", hasSideloadedCert = false))
        assertNotNull(takServerFormError("TAK", " ", "8089", "user", "pw", hasSideloadedCert = false))
    }

    @Test
    fun locationAccessSupportsPreciseAndApproximate() {
        assertEquals(LocationAccess.PRECISE, LocationAccess.of(fine = true, coarse = true))
        assertEquals(LocationAccess.APPROXIMATE, LocationAccess.of(fine = false, coarse = true))
        assertEquals(LocationAccess.NONE, LocationAccess.of(fine = false, coarse = false))
        assertTrue(LocationAccess.APPROXIMATE.granted)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, LocationAccess.PRECISE.priority)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, LocationAccess.APPROXIMATE.priority)
    }

    @Test
    fun sitxBaseUrl() {
        assertEquals("https://myorg.sitx.io", SitxClient.baseUrl(" My Org "))
        assertEquals("https://acme.sitx.io", SitxClient.baseUrl("Acme"))
        assertEquals("https://sitx.example.com", SitxClient.baseUrl("https://sitx.example.com/"))
        assertEquals("", SitxClient.baseUrl("  "))
    }
}
