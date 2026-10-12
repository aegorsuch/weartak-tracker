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

    fun port(value: String?): Int? =
        if (value == null) DEFAULT_PORT else value.toIntOrNull()?.takeIf { it in 1..65535 }
}