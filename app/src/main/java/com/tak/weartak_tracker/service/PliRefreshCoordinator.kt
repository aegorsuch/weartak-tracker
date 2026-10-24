package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.cot.Fix
import com.tak.weartak_tracker.data.LocationAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coalesces readiness notifications; a normal PLI already satisfies any pending refresh. Main-thread only. */
internal class PliRefreshCoordinator(
    private val scope: CoroutineScope,
    private val refresh: suspend () -> Unit,
    private val coalesce: suspend () -> Unit = { delay(100) },
) {
    private var pending: Job? = null

    fun request() {
        if (pending?.isActive == true) return
        pending = scope.launch {
            coalesce()
            pending = null
            refresh()
        }
    }

    fun onPliSent() {
        pending?.cancel()
        pending = null
    }
}

/** A cached precise fix cannot satisfy a refresh after the wearer switches to approximate access. */
internal suspend fun refreshCurrentPli(
    fix: Fix?,
    fixAccess: LocationAccess,
    access: LocationAccess,
    requestFix: () -> Unit,
    publish: suspend (Fix) -> Unit,
) {
    if (!access.granted) return
    if (fix == null || fixAccess != access) requestFix() else publish(fix)
}
