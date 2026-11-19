package com.tak.weartak_tracker.diagnostics

internal fun diagnosticEventForTag(tag: String): DiagnosticEvent = when (tag) {
    "TrackerService" -> DiagnosticEvent.TRACKING
    "LocationEngine" -> DiagnosticEvent.LOCATION
    "PhysioMonitor" -> DiagnosticEvent.SENSOR
    "TakServerManager", "TakCertificates", "TakChannelManager" -> DiagnosticEvent.TAK
    "SitxClient" -> DiagnosticEvent.SITX
    "MulticastPublisher", "MulticastChatReceiver", "MulticastWifiRequest" -> DiagnosticEvent.MULTICAST
    "DittoClient" -> DiagnosticEvent.DITTO
    "WearTAK-DebugStayAwake" -> DiagnosticEvent.WAKELOCK
    "ChatCommands" -> DiagnosticEvent.COMMAND
    "DeveloperSettings", "CustomAlerts", "MoppStatus" -> DiagnosticEvent.SETTINGS
    else -> DiagnosticEvent.ERROR
}

/** Preserve ordinary Android logging; persist only a fixed category/failure marker, never its arguments. */
object AppLog {
    fun i(tag: String, message: String): Int = android.util.Log.i(tag, message)

    fun w(tag: String, message: String, error: Throwable? = null): Int {
        Diagnostics.record(diagnosticEventForTag(tag), DiagnosticDetail.FAILED)
        return android.util.Log.w(tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null): Int {
        Diagnostics.record(diagnosticEventForTag(tag), DiagnosticDetail.FAILED)
        return android.util.Log.e(tag, message, error)
    }
}
