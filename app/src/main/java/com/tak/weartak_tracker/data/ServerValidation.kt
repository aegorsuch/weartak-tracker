package com.tak.weartak_tracker.data

/**
 * Validates the TAK Server form. Username/password are only needed for enrollment, so they may be left
 * empty when a client certificate has been sideloaded for this address. Returns an error message or null.
 */
fun takServerFormError(
    name: String,
    address: String,
    port: String,
    username: String,
    password: String,
    hasSideloadedCert: Boolean,
): String? {
    val p = port.trim().toIntOrNull()
    return when {
        name.isBlank() -> "Enter a connection name"
        address.isBlank() -> "Enter the server address"
        p == null || p !in 1..65535 -> "Enter a port from 1 to 65535"
        username.isBlank() != password.isBlank() -> "Enter both username and password"
        username.isBlank() && !hasSideloadedCert ->
            "Enter username/password or sideload certs/${address.trim()}.p12"
        else -> null
    }
}
