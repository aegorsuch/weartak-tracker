package com.tak.weartak_tracker.command

object ChatCommandValidation {
    const val DEFAULT_PORT = 8089
    private val CALLSIGN = Regex("[A-Za-z0-9][A-Za-z0-9 _.\\-]{0,31}")
    private val HOST = Regex("[A-Za-z0-9]([A-Za-z0-9.\\-]{0,251}[A-Za-z0-9])?")
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9 _.\\-]{0,31}")

    fun callsign(value: String?): String? = value?.trim()?.takeIf { CALLSIGN.matches(it) }

    fun serverName(value: String?): String? = value?.trim()?.takeIf { NAME.matches(it) }

    /** Bare host name or IPv4 address only: no scheme, path or port. */
    fun serverAddress(value: String?): String? = value?.trim()?.takeIf { HOST.matches(it) && ".." !in it }

    private val HEIGHT = Regex("(\\d)\\s*['-]\\s*(\\d{1,2})\"?")

    /** Feet and inches as the height picker offers them: feet 1-8, inches 0-11. Accepts 5'8", 5'8 and 5-8. */
    fun height(value: String?): Pair<Int, Int>? {
        val m = HEIGHT.matchEntire(value?.trim() ?: return null) ?: return null
        val feet = m.groupValues[1].toInt()
        val inches = m.groupValues[2].toInt()
        return if (feet in 1..8 && inches in 0..11) feet to inches else null
    }

    fun intIn(value: String?, range: IntRange): Int? = value?.trim()?.toIntOrNull()?.takeIf { it in range }

    fun port(value: String?): Int? =
        if (value == null) DEFAULT_PORT else value.toIntOrNull()?.takeIf { it in 1..65535 }
}