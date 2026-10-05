package com.tak.weartak_tracker.service

import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.data.ManualAlert
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manual-alert store-and-forward, matching WearTAK-CIV's CotDispatchService:
 * alerts/cancels are sent immediately when ready, otherwise queued and flushed FIFO
 * when an endpoint connects or a fresh location arrives.
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

    fun restore(alerts: List<ManualAlert>, queue: List<ManualAlert>) {
        this.alerts = alerts
        this.queue.clear()
        this.queue.addAll(queue)
        changed()
    }

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

        fun encode(alerts: List<ManualAlert>, queue: List<ManualAlert>): String =
            JSONObject().put("alerts", toJson(alerts)).put("queue", toJson(queue)).toString()

        fun decode(json: String?): Pair<List<ManualAlert>, List<ManualAlert>> {
            if (json.isNullOrBlank()) return emptyList<ManualAlert>() to emptyList()
            return runCatching {
                val o = JSONObject(json)
                fromJson(o.optJSONArray("alerts")) to fromJson(o.optJSONArray("queue"))
            }.getOrDefault(emptyList<ManualAlert>() to emptyList())
        }

        private fun toJson(items: List<ManualAlert>) = JSONArray().apply {
            items.forEach {
                put(
                    JSONObject().put("uid", it.uid).put("state", it.state.name).put("category", it.category)
                        .put("description", it.description).put("priority", it.priority)
                        .put("time", it.timeMillis).put("enqueued", it.enqueued),
                )
            }
        }

        private fun fromJson(arr: JSONArray?): List<ManualAlert> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ManualAlert(
                    uid = o.getString("uid"),
                    state = AlertState.valueOf(o.getString("state")),
                    category = o.optString("category", MANUAL_ALERT_CATEGORY),
                    description = o.optString("description", DEFAULT_DESCRIPTION),
                    priority = o.optInt("priority", 1),
                    timeMillis = o.optLong("time"),
                    enqueued = o.optBoolean("enqueued"),
                )
            }
        }
    }
}
