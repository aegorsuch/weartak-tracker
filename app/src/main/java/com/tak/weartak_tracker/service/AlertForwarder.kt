package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.data.ManualAlert
import java.util.UUID

/**
 * Manual-alert store-and-forward, matching WearTAK-CIV's CotDispatchService:
 * alerts/cancels are sent immediately when ready, otherwise queued and flushed FIFO
 * when an endpoint connects or a fresh location arrives. The queue lives in memory only.
 */
class AlertForwarder(
    private val send: suspend (ManualAlert) -> Boolean,
    private val onChanged: (alerts: List<ManualAlert>, queue: List<ManualAlert>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var alerts: List<ManualAlert> = emptyList()
        private set
    private val queue = ArrayDeque<ManualAlert>()
    val pending: List<ManualAlert> get() = queue.toList()

    fun newAlert(description: String) = ManualAlert(
        uid = UUID.randomUUID().toString(),
        state = AlertState.ALERT,
        category = MANUAL_ALERT_CATEGORY,
        description = description.trim().ifBlank { DEFAULT_DESCRIPTION },
        priority = 1,
        timeMillis = clock(),
        enqueued = false,
    )

    /** The cancel for the most recent active alert, or null when nothing is alerting. */
    fun cancelForLast(): ManualAlert? =
        alerts.filter { it.state == AlertState.ALERT }.maxByOrNull { it.timeMillis }
            ?.copy(state = AlertState.CANCEL, enqueued = false)

    /** Returns true when sent now, false when stored for later. */
    suspend fun submit(item: ManualAlert, ready: Boolean): Boolean {
        if (ready && sendAll(item)) return true
        enqueue(item)
        return false
    }

    /** Flushes queued items in order, stopping at the first failure. */
    suspend fun flush() {
        while (queue.isNotEmpty()) {
            val head = queue.first()
            if (!send(head)) break
            queue.removeFirst()
            sent(head)
        }
    }

    private suspend fun sendAll(item: ManualAlert): Boolean {
        flush()
        if (queue.isNotEmpty() || !send(item)) return false
        sent(item)
        return true
    }

    private fun sent(item: ManualAlert) {
        alerts = when (item.state) {
            AlertState.ALERT ->
                if (alerts.any { it.uid == item.uid }) alerts.map { if (it.uid == item.uid && it.state == AlertState.ALERT) it.copy(enqueued = false) else it }
                else alerts + item.copy(enqueued = false)
            AlertState.CANCEL -> alerts.filterNot { it.uid == item.uid }
        }
        changed()
    }

    private fun enqueue(item: ManualAlert) {
        val queued = item.copy(enqueued = true)
        queue.addLast(queued)
        alerts = when (item.state) {
            AlertState.ALERT -> alerts + queued
            AlertState.CANCEL -> alerts.map { if (it.uid == item.uid) it.copy(state = AlertState.CANCEL, enqueued = true) else it }
        }
        changed()
    }

    private fun changed() = onChanged(alerts, queue.toList())

    companion object {
        const val MANUAL_ALERT_CATEGORY = "Manual Alert"
        const val DEFAULT_DESCRIPTION = "Manual Alert Pressed by User"
    }
}
