package com.tak.weartak_tracker.cot

import com.tak.weartak_tracker.data.MoppStatus
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CotTime(val startCot: String, val staleCot: String) {
    companion object {
        fun now(staleSeconds: Long, nowMillis: Long = System.currentTimeMillis()): CotTime {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return CotTime(fmt.format(Date(nowMillis)), fmt.format(Date(nowMillis + staleSeconds * 1000L)))
        }
    }
}

data class Fix(
    val lat: Double,
    val lon: Double,
    val hae: Double,
    val ce: Float,
    val le: Float,
    val course: Float,
    val speed: Float,
    val timeMillis: Long,
)

enum class AlertState { ALERT, CANCEL }

/** Physio values for PLI; null fields are sent as `N/A` like WearTAK-CIV. Skin temperature is in degrees F. */
data class Physio(val heartRateBpm: Int?, val skinTempF: Float? = null, val exertion: Double? = null)

/** Builds the same PLI and emergency CoT as WearTAK-CIV. */
object CotBuilder {
    private const val DECLARATION = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?>"
    const val SELF_TYPE = "a-f-G-U-C"
    fun pliStaleSeconds(reportingIntervalSeconds: Int): Long =
        2L * reportingIntervalSeconds.coerceAtLeast(1) + 15L
    const val ALERT_STALE_SECONDS = 15L * 60L
    private const val FRESH_EMERGENCY_FIX_MILLIS = 3_000L
    /** Walking pace, used to grow ce for an older fix when the fix's own speed is lower or unknown. */
    private const val ASSUMED_DRIFT_MPS = 1.5f
    private const val UNKNOWN_LOCATION_ERROR_METERS = 9_999_999f
    private const val NA = "N/A"

    /** [physio] is null when monitoring is off. BATDOK gates only the `_atmist_` block. */
    fun pli(
        uid: String,
        callsign: String,
        team: String,
        role: String,
        battery: Int?,
        fix: Fix?,
        time: CotTime,
        physio: Physio? = null,
        includeBatdok: Boolean = true,
        ageYears: Int? = null,
        moppStatus: MoppStatus = MoppStatus.NONE,
    ): String {
        val ce = fix?.ce?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val le = fix?.le?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val point = listOf(
            "lat" to "%.6f".format(Locale.US, fix?.lat ?: 0.0),
            "lon" to "%.6f".format(Locale.US, fix?.lon ?: 0.0),
            "hae" to "%.1f".format(Locale.US, fix?.hae ?: 0.0),
            "ce" to ce, "le" to le,
        )
        val ppeRemarks = moppStatus.takeUnless { it == MoppStatus.NONE }?.label
        val wearerDetail = if (physio != null) {
            physioDetail(uid, physio, time, includeBatdok, ageYears, ppeRemarks)
        } else ppeRemarks?.let { element("remarks", content = esc(it)) } ?: ""
        val detail =
            element("status", listOf("readiness" to "true", "battery" to battery)) +
                wearerDetail +
                element("contact", listOf("endpoint" to "*:-1:stcp", "callsign" to esc(callsign))) +
                element("__group", listOf("role" to esc(role), "name" to esc(team))) +
                element("track", listOf("course" to (fix?.course?.toInt() ?: 0), "speed" to (fix?.speed?.toInt() ?: 0))) +
                element("takv", listOf("platform" to "WearTAK-Tracker", "device" to "WEAROS"))
        return event(
            listOf(
                "version" to "2.0", "uid" to esc(uid), "type" to SELF_TYPE, "time" to time.startCot,
                "start" to time.startCot, "stale" to time.staleCot, "how" to "m-g",
            ),
            point, detail,
        )
    }

    fun emergency(
        alertUid: String,
        state: AlertState,
        category: String,
        description: String,
        priority: Int,
        deviceUid: String,
        callsign: String,
        fix: Fix?,
        time: CotTime,
    ): String {
        val eventMillis = Instant.parse(time.startCot).toEpochMilli()
        // Any valid fix beats 0,0. GPS and system clocks can disagree, so a fix dated "in the future" counts as current.
        val location = fix?.takeIf {
            it.lat.isFinite() && it.lat in -90.0..90.0 &&
                it.lon.isFinite() && it.lon in -180.0..180.0
        }
        val ageMillis = location?.let { (eventMillis - it.timeMillis).coerceAtLeast(0) } ?: 0
        val ce = location?.ce?.takeIf { it.isFinite() && it >= 0f }?.let { base ->
            // An older fix's error grows with how far the wearer could have moved since it was taken.
            if (ageMillis < FRESH_EMERGENCY_FIX_MILLIS) base
            else minOf(
                base + maxOf(location.speed.takeIf { it.isFinite() } ?: 0f, ASSUMED_DRIFT_MPS) * ageMillis / 1000f,
                UNKNOWN_LOCATION_ERROR_METERS,
            )
        }
        val point = listOf(
            "lat" to (location?.lat ?: 0.0), "lon" to (location?.lon ?: 0.0),
            "hae" to (location?.hae?.takeIf { it.isFinite() } ?: 0.0),
            "ce" to (ce ?: UNKNOWN_LOCATION_ERROR_METERS),
            "le" to (location?.le?.takeIf { it.isFinite() && it >= 0f } ?: UNKNOWN_LOCATION_ERROR_METERS),
        )
        val biometrics = element(
            "biometrics",
            listOf(
                "alertUid" to esc(alertUid),
                "alertState" to state.name,
                "alertCategory" to esc(category),
                "alertPriority" to priority,
                "alertDescription" to esc(description),
            ),
            element(
                "device",
                content = element("model", content = "WEAROS") + element("uid", content = esc(deviceUid)),
            ),
        )
        val detail = if (state == AlertState.CANCEL) {
            biometrics + element("emergency", listOf("cancel" to "true"), esc(callsign))
        } else {
            biometrics +
                element("link", listOf("uid" to esc(deviceUid), "type" to SELF_TYPE, "relation" to "p-p")) +
                element("contact", listOf("callsign" to "${esc(callsign)}&#10;${esc(description)}")) +
                element("emergency", listOf("type" to esc(StandardEmergencyType.emergencyType(description))), esc(callsign)) +
                element("usericon", listOf("iconsetpath" to "911 Alert")) +
                element("color", listOf("argb" to "-1"))
        }
        val type = if (state == AlertState.CANCEL) StandardEmergencyType.CANCEL_COT_TYPE else StandardEmergencyType.cotType(description)
        return event(
            listOf(
                "version" to "2.0", "uid" to esc(alertUid), "type" to type, "time" to time.startCot,
                "start" to time.startCot, "stale" to time.staleCot, "how" to "h-e", "access" to "Undefined",
            ),
            point, detail,
        )
    }

    /** CIV's physio remarks plus saved PPE, BATDOK and `<biometrics>` device blocks. */
    private fun physioDetail(
        uid: String, physio: Physio, time: CotTime, includeBatdok: Boolean, ageYears: Int?, ppeRemarks: String?,
    ): String {
        val hr = physio.heartRateBpm?.takeIf { it > 0 }?.toString() ?: NA
        val skinF = physio.skinTempF?.takeIf { it.isFinite() && it != -1f }
        val skt = skinF?.let { String.format(Locale.US, "%.1f", it) } ?: NA
        // CIV transmits a reserve fraction (0.5 means 50%), including in its legacy "%" remarks.
        val exert = physio.exertion?.takeIf { hr != NA && it.isFinite() && it >= 0 }
            ?.let { String.format(Locale.US, "%.2f", it) } ?: NA
        val remarks = element(
            "remarks",
            content = esc(listOfNotNull("Exert:$exert%;HR:$hr;SkinTemp:$skt", ppeRemarks).joinToString("\n")),
        )
        val readings = buildList {
            if (hr != NA) add("HR,$hr")
            if (skinF != null) {
                val celsius = String.format(Locale.US, "%.1f", (skinF - 32f) * 5f / 9f).removeSuffix(".0")
                add("Temp,$skt (F) $celsius (C)")
            }
        }
        val atmist = if (!includeBatdok || readings.isEmpty()) "" else element(
            "_atmist_",
            listOfNotNull(
                ageYears?.takeIf { it >= 0 }?.let { "age" to it },
                "timeOfIncident" to time.startCot,
            ),
            readings.mapIndexed { index, reading ->
                // A vital sign is observed now, not at the PLI's future stale time.
                element(
                    "vitalSign", listOf("index" to index, "timestamp" to time.startCot),
                    "($reading,${Instant.parse(time.startCot).toEpochMilli()})",
                )
            }.joinToString(""),
        )
        return remarks + atmist +
            element(
                "biometrics",
                content = element(
                    "device",
                    content = element("model", content = "WEAROS") + element("uid", content = esc(uid)) +
                        element("hr", content = hr) + element("skt", content = skt) + element("exert", content = exert),
                ),
            )
    }

    /** PLI for the mesh: the contact endpoint points ATAK's direct chat at the mesh chat group, like WearOS. */
    fun meshContact(pliXml: String): String = pliXml.replace("endpoint='*:-1:stcp'", "endpoint='$MESH_CHAT_ENDPOINT'")

    const val MESH_CHAT_ENDPOINT = "224.10.10.1:17012:udp"

    /** GeoChat reply; [groupMembers] preserves a group conversation instead of opening a direct chat. */
    fun chat(
        senderUid: String,
        senderCallsign: String,
        toUid: String,
        toCallsign: String,
        message: String,
        messageId: String = java.util.UUID.randomUUID().toString(),
        nowMillis: Long = System.currentTimeMillis(),
        groupMembers: List<String>? = null,
    ): String {
        val t = CotTime.now(60, nowMillis).startCot
        val broadcast = groupMembers != null && toUid == "All Chat Rooms"
        val members = if (broadcast) listOf(toUid)
        else (groupMembers ?: listOf(toUid)).filter { it != senderUid && (groupMembers == null || it != toUid) }.distinct()
        val chatGroup = element("chatgrp", listOf("uid0" to esc(senderUid), "id" to esc(toUid)) +
            members.mapIndexed { i, uid -> "uid${i + 1}" to esc(uid) })
        val chat = element(
            "__chat",
            listOf(
                "parent" to "RootContactGroup", "groupOwner" to "false", "messageId" to esc(messageId),
                "chatroom" to esc(toCallsign), "id" to esc(toUid), "senderCallsign" to esc(senderCallsign),
            ),
            chatGroup,
        )
        val destinations = members.joinToString("") { element("dest", listOf("uid" to esc(it))) }
        val detail = chat +
            element("link", listOf("uid" to esc(senderUid), "type" to SELF_TYPE, "relation" to "p-p")) +
            element("remarks", listOf("source" to esc(senderUid), "to" to esc(toUid), "time" to t), esc(message)) +
            (if (broadcast) "" else element("marti", content = destinations))
        return event(
            listOf(
                "version" to "2.0", "uid" to esc("GeoChat.$senderUid.$toUid.$messageId"), "type" to "b-t-f",
                "time" to t, "start" to t, "stale" to t, "how" to "h-g-i-g-o", "access" to "Undefined",
            ),
            listOf("lat" to "0.0", "lon" to "0.0", "hae" to "0.0", "ce" to 9999, "le" to 9999),
            detail,
        )
    }
    private fun element(name: String, attributes: List<Pair<String, Any?>> = emptyList(), content: String? = null) =
        buildString {
            append('<').append(name)
            attributes.forEach { (k, v) -> append(' ').append(k).append("='").append(v).append('\'') }
            if (content == null) append("/>") else append('>').append(content).append("</").append(name).append('>')
        }

    private fun event(attributes: List<Pair<String, Any?>>, point: List<Pair<String, Any?>>, detail: String) =
        DECLARATION + element("event", attributes, element("point", point) + element("detail", content = detail))

    internal fun esc(value: String): String = buildString(value.length) {
        value.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\'' -> append("&apos;")
                '"' -> append("&quot;")
                else -> append(it)
            }
        }
    }
}
