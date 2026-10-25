package com.tak.weartak_tracker.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class DiagnosticEvent {
    DIAGNOSTICS, APP, UI, TRACKING, LOCATION, SPEED, REPORTING, PLI, SENSOR,
    POWER, WAKELOCK, TAK, SITX, MULTICAST, DITTO, COMMAND, SETTINGS, ERROR, CRASH,
}

enum class DiagnosticDetail {
    ENABLED, DISABLED, STARTED, STOPPED, RESUMED, PAUSED, FIX, TIMEOUT,
    PRECISE, APPROXIMATE, UNKNOWN, STATIONARY, MOVING, DUE, SENT,
    AVAILABLE, UNAVAILABLE, LOW_BATTERY, NORMAL, POWER_SAVE, CONNECTED,
    DISCONNECTED, CHANGED, FAILED, DROPPED,
}

internal class DiagnosticsConsent {
    @Volatile var enabled = false
        private set
    private var generation = 0L

    @Synchronized
    fun setEnabled(value: Boolean): Boolean {
        if (value == enabled) return false
        generation++
        enabled = value
        return true
    }

    @Synchronized fun token(): Long? = if (enabled) generation else null
    @Synchronized fun permits(token: Long): Boolean = enabled && token == generation
}

/** Only fixed vocabulary and timestamps can enter the journal; no free-text payload is accepted. */
internal class DiagnosticsJournal(private val directory: File, private val buildCode: Int) {
    private data class Repetition(var lastWrite: Long, var suppressed: Int = 0)
    private val repetitions = mutableMapOf<Pair<DiagnosticEvent, DiagnosticDetail>, Repetition>()

    init {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create diagnostics directory")
        for (name in listOf(CURRENT, PREVIOUS)) {
            val file = File(directory, name)
            if (file.length() > MAX_FILE_BYTES) file.writeText("")
        }
    }

    @Synchronized
    fun record(event: DiagnosticEvent, detail: DiagnosticDetail, wallMillis: Long, elapsedMillis: Long) {
        val key = event to detail
        val repetition = repetitions[key]
        if (repetition != null && elapsedMillis >= repetition.lastWrite &&
            elapsedMillis - repetition.lastWrite < REPETITION_WINDOW_MS
        ) {
            repetition.suppressed = (repetition.suppressed.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return
        }
        val line = "$wallMillis\t$elapsedMillis\t$buildCode\t${event.name}\t${detail.name}\t${repetition?.suppressed ?: 0}\n"
        val current = File(directory, CURRENT)
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (current.length() + bytes.size > MAX_FILE_BYTES) {
            Files.move(current.toPath(), File(directory, PREVIOUS).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        current.appendBytes(bytes)
        repetitions[key] = Repetition(elapsedMillis)
    }

    companion object {
        const val MAX_FILE_BYTES = 128 * 1024L
        const val REPETITION_WINDOW_MS = 30_000L
        const val CURRENT = "diagnostics-current.log"
        const val PREVIOUS = "diagnostics-previous.log"
    }
}
