package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.cot.Fix

/** Source-owned successful connection generations, not UI aggregate endpoint state. Main-thread only. */
internal class OutboundPliRefresh {
    private var routes = emptyMap<String, Long>()
    private val pending = mutableMapOf<String, Long>()
    private var fixRequested = emptyMap<String, Long>()

    val needed: Boolean get() = pending.isNotEmpty()

    fun update(readyRoutes: Map<String, Long>) {
        pending.keys.retainAll(readyRoutes.keys)
        readyRoutes.forEach { (source, generation) ->
            if (routes[source] != generation) pending[source] = generation
        }
        routes = readyRoutes.toMap()
    }

    fun snapshot(): Map<String, Long> = pending.toMap()

    fun takeFixRequest(): Boolean {
        if (pending.isEmpty()) return true
        if (fixRequested == pending) return false
        fixRequested = snapshot()
        return true
    }

    fun onPublished(sent: Map<String, Long>) {
        sent.forEach { (source, generation) ->
            if (pending[source] == generation) pending.remove(source)
        }
    }
}

/** Uses Android's acquisition clock: late/cached callbacks never make an old fix current. */
internal fun Fix.isCurrentPliFix(nowElapsed: Long, freshnessMillis: Long): Boolean {
    val sample = elapsedRealtimeMillis ?: return false
    return sample > 0 && sample <= nowElapsed && nowElapsed - sample <= freshnessMillis &&
        lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0 &&
        ce.isFinite() && ce >= 0f
}
