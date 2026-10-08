package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.TakServerConfig
import com.tak.weartak_tracker.ui.serversAlphabetically
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerListTest {
    @Test
    fun sortsNamesIgnoringCaseWithoutChangingSavedOrderOrServerSettings() {
        val zulu = TakServerConfig(id = "z", name = "Zulu", enabled = false)
        val alpha = TakServerConfig(id = "a", name = "alpha")
        val bravo = TakServerConfig(id = "b", name = "Bravo")
        val saved = listOf(zulu, alpha, bravo)

        assertEquals(listOf(alpha, bravo, zulu), serversAlphabetically(saved))
        assertEquals(listOf(zulu, alpha, bravo), saved)
    }

    @Test
    fun equalNamesKeepSavedOrderAndDuplicateEntries() {
        val first = TakServerConfig(id = "first", name = "Alpha")
        val second = TakServerConfig(id = "second", name = "alpha")
        val third = TakServerConfig(id = "third", name = "Alpha")

        assertEquals(listOf(first, second, third), serversAlphabetically(listOf(first, second, third)))
        assertEquals(emptyList<TakServerConfig>(), serversAlphabetically(emptyList()))
    }
}
