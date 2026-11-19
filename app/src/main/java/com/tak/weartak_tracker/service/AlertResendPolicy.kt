package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.cot.AlertState

/**
 * Keeps each manual alert to a single delivery. The one exception is an alert sent before any fix
 * existed: the first fix afterwards sends it exactly once more with a real position.
 */
class AlertResendPolicy {
    private val unpositioned = mutableSetOf<String>()

    fun onSent(uid: String, state: AlertState, hadFix: Boolean) {
        if (state == AlertState.ALERT && !hadFix) unpositioned += uid else unpositioned -= uid
    }

    /** Alerts to resend now that a fix exists, limited to the still-active [activeUids]. */
    fun dueOnFix(activeUids: Collection<String>): List<String> {
        unpositioned.retainAll(activeUids.toSet())
        return activeUids.filter { it in unpositioned }
    }
}
