package com.tak.weartak_tracker

import com.tak.weartak_tracker.service.PliRefreshCoordinator
import com.tak.weartak_tracker.service.refreshCurrentPli
import com.tak.weartak_tracker.cot.Fix
import com.tak.weartak_tracker.data.LocationAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PliRefreshCoordinatorTest {
    @Test
    fun refreshRequiresActualFixAndCurrentLocationAccessWithoutFabricatingCoordinates() = runBlocking {
        val actual = Fix(1.0, 2.0, 3.0, 4f, 5f, 6f, 7f, 123L)
        val sent = mutableListOf<Fix>()
        var requests = 0
        suspend fun refresh(fix: Fix?, from: LocationAccess, current: LocationAccess) =
            refreshCurrentPli(fix, from, current, { requests++ }, { sent += it })
        refresh(actual, LocationAccess.PRECISE, LocationAccess.NONE)
        assertEquals(0, requests)
        assertEquals(emptyList<Fix>(), sent)
        refresh(null, LocationAccess.NONE, LocationAccess.PRECISE)
        refresh(actual, LocationAccess.PRECISE, LocationAccess.APPROXIMATE)
        assertEquals(2, requests)
        assertEquals(emptyList<Fix>(), sent)
        refresh(actual, LocationAccess.PRECISE, LocationAccess.PRECISE)
        refresh(actual, LocationAccess.APPROXIMATE, LocationAccess.APPROXIMATE)
        assertEquals(listOf(actual, actual), sent)
        assertEquals(2, requests)
    }

    @Test
    fun simultaneousReadySignalsCoalesceAndRefreshReadsCurrentState() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            var gate = CompletableDeferred<Unit>()
            var current = "old"
            val sent = mutableListOf<String>()
            val refresh = PliRefreshCoordinator(scope, { sent += current }, { gate.await() })
            repeat(5) { refresh.request() }
            assertEquals(emptyList<String>(), sent)
            current = "latest"
            gate.complete(Unit)
            assertEquals(listOf("latest"), sent)
            gate = CompletableDeferred()
            current = "reconnected"
            refresh.request()
            gate.complete(Unit)
            assertEquals(listOf("latest", "reconnected"), sent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun freshLocationPliSatisfiesPendingRefreshWithoutDuplicate() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            var sends = 0
            val gate = CompletableDeferred<Unit>()
            val refresh = PliRefreshCoordinator(scope, { sends++ }, { gate.await() })
            refresh.request()
            refresh.onPliSent()
            gate.complete(Unit)
            assertEquals(0, sends)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun serviceShutdownCancelsPendingRefresh() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        var sends = 0
        val gate = CompletableDeferred<Unit>()
        val refresh = PliRefreshCoordinator(scope, { sends++ }, { gate.await() })
        refresh.request()
        scope.cancel()
        gate.complete(Unit)
        assertEquals(0, sends)
    }
}
