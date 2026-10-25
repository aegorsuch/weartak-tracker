package com.tak.weartak_tracker

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.tak.weartak_tracker.data.DebugPreferences
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.sortedByDisplayedLabel
import com.tak.weartak_tracker.diagnostics.DiagnosticDetail
import com.tak.weartak_tracker.diagnostics.DiagnosticEvent
import com.tak.weartak_tracker.diagnostics.Diagnostics
import com.tak.weartak_tracker.diagnostics.DiagnosticsConsent
import com.tak.weartak_tracker.diagnostics.DiagnosticsJournal
import com.tak.weartak_tracker.diagnostics.DiagnosticsStatus
import com.tak.weartak_tracker.diagnostics.diagnosticEventForTag
import com.tak.weartak_tracker.ui.DeveloperEntry
import com.tak.weartak_tracker.ui.developerEntries
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ThreadPoolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun consentDefaultsOffAndOldQueuedEventsCannotSurviveDisableOrReenable() {
        val consent = DiagnosticsConsent()
        assertNull(consent.token())
        assertTrue(consent.setEnabled(true))
        val first = consent.token()!!
        assertTrue(consent.permits(first))
        assertFalse(consent.setEnabled(true))
        assertEquals(first, consent.token())
        consent.setEnabled(false)
        assertFalse(consent.permits(first))
        assertNull(consent.token())
        consent.setEnabled(true)
        assertFalse(consent.permits(first))
        assertTrue(consent.permits(consent.token()!!))
    }

    @Test
    fun disabledLoggerDoesNotStartWriterAndQueueHasAFixedBound() {
        Diagnostics.record(DiagnosticEvent.LOCATION, DiagnosticDetail.FIX)
        assertEquals(DiagnosticsStatus.OFF, Diagnostics.status.value)
        val field = Diagnostics::class.java.getDeclaredField("writer").apply { isAccessible = true }
        val writer = field.get(Diagnostics) as ThreadPoolExecutor
        assertEquals(0, writer.poolSize)
        assertEquals(64, writer.queue.remainingCapacity())
        assertEquals(1, writer.maximumPoolSize)
    }

    @Test
    fun flagsSurviveDataStoreRecreationAndUnlockDoesNotGrantOrChangeNetworkLock() = runBlocking {
        assertFalse(TrackerConfig().diagnosticsEnabled)
        assertFalse(DebugPreferences.developerMode(emptyPreferences()))
        assertFalse(DebugPreferences.diagnostics(emptyPreferences()))
        val file = File(temporary.root, "debug.preferences_pb")
        val networkLock = booleanPreferencesKey("network_preferences_locked")
        suspend fun roundTrip(developer: Boolean, diagnostics: Boolean) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
                store.edit {
                    it[networkLock] = true
                    DebugPreferences.write(it, developer, diagnostics)
                }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
            val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reopened = PreferenceDataStoreFactory.create(scope = reopenedScope, produceFile = { file })
                val saved = reopened.data.first()
                assertEquals(developer, DebugPreferences.developerMode(saved))
                assertEquals(diagnostics, DebugPreferences.diagnostics(saved))
                assertEquals(true, saved[networkLock])
            } finally {
                reopenedScope.coroutineContext.job.cancelAndJoin()
            }
        }
        roundTrip(true, true)
        roundTrip(false, true)
        roundTrip(false, false)
        assertTrue(TrackerConfig(networkPreferencesLocked = true).copy(developerMode = true).networkPreferencesLocked)
    }

    @Test
    fun warningSourcesReduceToFixedCategoriesAndUnknownTagsCannotLeakThrough() {
        val privateInput = "https://example.invalid/server?token=private 1.234,2.345"
        assertEquals(DiagnosticEvent.ERROR, diagnosticEventForTag(privateInput))
        assertEquals(DiagnosticEvent.TAK, diagnosticEventForTag("TakCertificates"))
        assertEquals(DiagnosticEvent.SETTINGS, diagnosticEventForTag("MoppStatus"))
        val directory = temporary.newFolder()
        DiagnosticsJournal(directory, 1).record(diagnosticEventForTag(privateInput), DiagnosticDetail.FAILED, 1, 1)
        val saved = File(directory, DiagnosticsJournal.CURRENT).readText()
        assertFalse(saved.contains(privateInput))
        assertFalse(saved.contains("token"))
        assertTrue(saved.contains("\tERROR\tFAILED\t"))
    }

    @Test
    fun journalPersistsOnlyFixedVocabularyAndTimestampsAndSurvivesRecreation() {
        val directory = temporary.newFolder()
        DiagnosticsJournal(directory, 5860037).record(DiagnosticEvent.LOCATION, DiagnosticDetail.FIX, 10_000, 1000)
        DiagnosticsJournal(directory, 5860038).record(DiagnosticEvent.APP, DiagnosticDetail.STARTED, 20_000, 2000)
        val lines = File(directory, DiagnosticsJournal.CURRENT).readLines()
        assertEquals(2, lines.size)
        lines.forEach { line ->
            val fields = line.split('\t')
            assertEquals(6, fields.size)
            fields.take(3).forEach { assertNotNull(it.toLongOrNull()) }
            assertTrue(DiagnosticEvent.entries.any { it.name == fields[3] })
            assertTrue(DiagnosticDetail.entries.any { it.name == fields[4] })
            assertNotNull(fields[5].toIntOrNull())
        }
        val method = DiagnosticsJournal::class.java.declaredMethods.single { it.name == "record" }
        assertFalse(method.parameterTypes.contains(String::class.java))
    }

    @Test
    fun repetitionsAreRateLimitedButDistinctStatesRemainVisible() {
        val directory = temporary.newFolder()
        val journal = DiagnosticsJournal(directory, 1)
        journal.record(DiagnosticEvent.LOCATION, DiagnosticDetail.FIX, 1000, 1000)
        journal.record(DiagnosticEvent.LOCATION, DiagnosticDetail.FIX, 2000, 2000)
        journal.record(DiagnosticEvent.LOCATION, DiagnosticDetail.FIX, 3000, 3000)
        journal.record(DiagnosticEvent.LOCATION, DiagnosticDetail.FAILED, 4000, 4000)
        journal.record(DiagnosticEvent.LOCATION, DiagnosticDetail.FIX, 31_000, 31_000)
        val lines = File(directory, DiagnosticsJournal.CURRENT).readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("\tFAILED\t"))
        assertTrue(lines.last().endsWith("\t2"))
    }

    @Test
    fun rotatingRetentionAndCorruptOversizedFilesAreStrictlyBounded() {
        val directory = temporary.newFolder()
        val journal = DiagnosticsJournal(directory, 1)
        repeat(12_000) { index ->
            val time = index.toLong() * DiagnosticsJournal.REPETITION_WINDOW_MS
            journal.record(DiagnosticEvent.REPORTING, DiagnosticDetail.DUE, time, time)
        }
        assertEquals(2, directory.listFiles()!!.size)
        directory.listFiles()!!.forEach { assertTrue(it.length() <= DiagnosticsJournal.MAX_FILE_BYTES) }
        File(directory, DiagnosticsJournal.CURRENT).writeBytes(ByteArray(DiagnosticsJournal.MAX_FILE_BYTES.toInt() + 1))
        File(directory, DiagnosticsJournal.PREVIOUS).writeBytes(ByteArray(DiagnosticsJournal.MAX_FILE_BYTES.toInt() + 1))
        DiagnosticsJournal(directory, 2).record(DiagnosticEvent.APP, DiagnosticDetail.STARTED, 1, 1)
        directory.listFiles()!!.forEach { assertTrue(it.length() <= DiagnosticsJournal.MAX_FILE_BYTES) }
    }

    @Test(expected = IOException::class)
    fun storageFailureIsNotASuccessShapedFallback() {
        DiagnosticsJournal(temporary.newFile(), 1)
    }

    @Test
    fun developerRowsFollowLocalizedVisibleLabelsWithoutChangingTheirActions() {
        val labels = mapOf(
            DeveloperEntry.DIAGNOSTICS to "Zustand",
            DeveloperEntry.STAY_AWAKE to "Änderung",
            DeveloperEntry.BUILD to "Bau",
        )
        val sorted = sortedByDisplayedLabel(labels.keys.toList(), Locale.GERMAN) { labels.getValue(it) }
        assertEquals(listOf(DeveloperEntry.STAY_AWAKE, DeveloperEntry.BUILD, DeveloperEntry.DIAGNOSTICS), sorted)
        assertEquals(13, DeveloperEntry.entries.size)
        assertEquals(13, DeveloperEntry.entries.map { it.labelRes }.distinct().size)
    }

    @Test
    fun simpleTogglesLeadWithLoggingThenStayAwakeAndRemainingActionsStaySorted() {
        val labels = DeveloperEntry.entries.associateWith { it.name }.toMutableMap().apply {
            this[DeveloperEntry.DIAGNOSTICS] = "Logging"
            this[DeveloperEntry.STAY_AWAKE] = "Stay awake"
        }
        val ordered = developerEntries(labels, Locale.ENGLISH)
        assertEquals(listOf(DeveloperEntry.DIAGNOSTICS, DeveloperEntry.STAY_AWAKE), ordered.take(2))
        assertEquals(sortedByDisplayedLabel(ordered.drop(2), Locale.ENGLISH) { labels.getValue(it) }, ordered.drop(2))
        assertEquals(DeveloperEntry.entries.toSet(), ordered.toSet())
        assertTrue(DeveloperEntry.RESUME_AFTER_UPDATE in ordered.drop(2))
        labels[DeveloperEntry.DIAGNOSTICS] = "Zustand"
        labels[DeveloperEntry.STAY_AWAKE] = "Änderung"
        assertEquals(listOf(DeveloperEntry.STAY_AWAKE, DeveloperEntry.DIAGNOSTICS), developerEntries(labels, Locale.GERMAN).take(2))
    }
}
