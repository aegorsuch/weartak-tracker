package com.tak.weartak_tracker.transport

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Called only after a staged identity has authenticated an owned candidate TLS socket. */
internal suspend fun commitVerifiedIdentity(
    stillValid: () -> Boolean,
    commit: suspend () -> Boolean,
    activate: () -> Unit,
    rollback: () -> Unit,
): Boolean {
    currentCoroutineContext().ensureActive()
    if (!stillValid()) return false
    var attempted = false
    var activated = false
    try {
        attempted = true
        if (!commit()) return false
        currentCoroutineContext().ensureActive()
        if (!stillValid()) return false
        activate()
        activated = true
        return true
    } finally {
        if (attempted && !activated) rollback()
    }
}
