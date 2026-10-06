package com.tak.weartak_tracker.cot

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
    const val PLI_STALE_MULTIPLIER = 3L
    const val ALERT_STALE_SECONDS = 15L * 60L
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
    ): String {
        val ce = fix?.ce?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val le = fix?.le?.takeIf { !it.isNaN() }?.toInt() ?: 9999
        val point = listOf(
            "lat" to "%.6f".format(Locale.US, fix?.lat ?: 0.0),
            "lon" to "%.6f".format(Locale.US, fix?.lon ?: 0.0),
            "hae" to "%.1f".format(Locale.US, fix?.hae ?: 0.0),
            "ce" to ce, "le" to le,
        )
        val detail =
            element("status", listOf("readiness" to "true", "battery" to battery)) +
                (physio?.let { physioDetail(uid, it, time, includeBatdok, ageYears) } ?: "") +
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
        val point = listOf(
            "lat" to (fix?.lat ?: 0.0), "lon" to (fix?.lon ?: 0.0), "hae" to (fix?.hae ?: 0.0),
            "ce" to (fix?.ce?.takeUnless { it.isNaN() } ?: 9_999_999f),
            "le" to (fix?.le?.takeUnless { it.isNaN() } ?: 9_999_999f),
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
                element("emergency", listOf("type" to esc(description)), esc(callsign)) +
                element("usericon", listOf("iconsetpath" to "911 Alert")) +
                element("color", listOf("argb" to "-1"))
        }
        val type = if (state == AlertState.CANCEL) "b-a-o-can" else "b-a-o"
        return event(
            listOf(
                "version" to "2.0", "uid" to esc(alertUid), "type" to type, "time" to time.startCot,
                "start" to time.startCot, "stale" to time.staleCot, "how" to "h-e", "access" to "Undefined",
            ),
            point, detail,
        )
    }

    /** CIV's physio remarks plus BATDOK and `<biometrics>` device blocks. */
    private fun physioDetail(uid: String, physio: Physio, time: CotTime, includeBatdok: Boolean, ageYears: Int?): String {
        val hr = physio.heartRateBpm?.takeIf { it > 0 }?.toString() ?: NA
        val skinF = physio.skinTempF?.takeIf { it.isFinite() && it != -1f }
        val skt = skinF?.let { String.format(Locale.US, "%.1f", it) } ?: NA
        // CIV transmits a reserve fraction (0.5 means 50%), including in its legacy "%" remarks.
        val exert = physio.exertion?.takeIf { hr != NA && it.isFinite() && it >= 0 }
            ?.let { String.format(Locale.US, "%.2f", it) } ?: NA
        val remarks = element("remarks", content = "Exert:$exert%;HR:$hr;SkinTemp:$skt")
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
