package com.tak.weartak_tracker

import com.tak.weartak_tracker.command.ChatCommand
import com.tak.weartak_tracker.command.ChatCommandRequest
import com.tak.weartak_tracker.command.CustomAlertSink
import com.tak.weartak_tracker.command.LoadProfileHandler
import com.tak.weartak_tracker.command.ProfileHandler
import com.tak.weartak_tracker.command.ProfilePresetParser
import com.tak.weartak_tracker.command.ProfileStore
import com.tak.weartak_tracker.cot.AlertState
import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.data.CustomAlerts
import com.tak.weartak_tracker.data.ProfilePresets
import com.tak.weartak_tracker.data.TrackerConfig
import com.tak.weartak_tracker.data.manualAlertOptions
import java.util.Locale
import com.tak.weartak_tracker.service.AlertForwarder
import com.tak.weartak_tracker.service.AlertResendPolicy
import com.tak.weartak_tracker.ui.CUSTOM_ALERT_HINT
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAlertTest {
    @Test
    fun pickerCombinesBuiltinsAndCustomsAlphabeticallyWithoutChangingSavedOrderOrLabels() {
        val saved = listOf("Zulu", "alpha", "Bravo")
        val options = manualAlertOptions(listOf("Vehicle", "911 Alert", "Injury"), saved, Locale.US)
        assertEquals(listOf("911 Alert", "alpha", "Bravo", "Injury", "Vehicle", "Zulu"), options.map { it.label })
        assertEquals(listOf("Zulu", "alpha", "Bravo"), saved)
        assertEquals(listOf("alpha", "Bravo", "Zulu"), options.filter { it.custom }.map { it.label })
        assertNull(CustomAlerts.add(saved, "ALPHA"))
        assertNull(CustomAlerts.add(saved, "injury"))
    }

    @Test
    fun sortedCustomDeletionTargetsOriginalLabelAndLeavesPresetsAndOtherSavedAlerts() {
        val saved = listOf("Zulu", "Alpha", "Bravo")
        val target = manualAlertOptions(listOf("Injury"), saved, Locale.US).first { it.label == "Bravo" }
        assertTrue(target.custom)
        val remaining = CustomAlerts.remove(saved, target.label)
        assertEquals(listOf("Zulu", "Alpha"), remaining)
        assertEquals(listOf("Alpha", "Injury", "Zulu"), manualAlertOptions(listOf("Injury"), remaining, Locale.US).map { it.label })
        assertTrue(!manualAlertOptions(listOf("Injury"), saved).first { it.label == "Injury" }.custom)
    }

    @Test
    fun pickerUsesRequestedLocaleAndStableCaseInsensitiveOrdering() {
        val options = manualAlertOptions(listOf("zebra"), listOf("alpha", "Alpha", "Älg"), Locale.forLanguageTag("sv-SE"))
        assertEquals(listOf("alpha", "Alpha", "zebra", "Älg"), options.map { it.label })
    }

    private val time = CotTime.now(CotBuilder.ALERT_STALE_SECONDS, 0)

    private fun alertXml(description: String, state: AlertState = AlertState.ALERT) =
        CotBuilder.emergency("a1", state, AlertForwarder.MANUAL_ALERT_CATEGORY, description, 1, "u1", "cs", null, time)

    @Test
    fun standardAlertsUseAtakEmergencyTypes() {
        mapOf(
            "911 Alert" to ("b-a-o-tbl" to "911 Alert"),
            "Ring The Bell" to ("b-a-o-pan" to "Ring The Bell"),
            "Geofence Breached" to ("b-a-g" to "Geo-fence Breached"),
            "In Contact" to ("b-a-o-opn" to "Troops In Contact"),
        ).forEach { (label, expected) ->
            val xml = alertXml(label)
            assertTrue(label, xml.contains("type='${expected.first}'"))
            assertTrue(label, xml.contains("<emergency type='${expected.second}'>cs</emergency>"))
            assertTrue(label, xml.contains("callsign='cs&#10;$label'"))
        }
    }

    @Test
    fun otherPresetsAndCustomAlertsStayGenericWithTheirOwnText() {
        listOf("Gunshot", "UAS", "Vehicle", "Need water", AlertForwarder.DEFAULT_DESCRIPTION).forEach { label ->
            val xml = alertXml(label)
            assertTrue(label, xml.contains("type='b-a-o'"))
            assertTrue(label, xml.contains("<emergency type='$label'>cs</emergency>"))
            assertTrue(xml.contains("alertCategory='Manual Alert'") && xml.contains("alertPriority='1'"))
        }
    }

    @Test
    fun cancelKeepsCancelType() {
        val xml = alertXml("911 Alert", AlertState.CANCEL)
        assertTrue(xml.contains("type='b-a-o-can'") && xml.contains("<emergency cancel='true'>cs</emergency>"))
    }

    @Test
    fun alertWithPositionIsNeverResentOnNewerFixes() {
        val policy = AlertResendPolicy()
        policy.onSent("a1", AlertState.ALERT, hadFix = true)
        repeat(3) { assertEquals(emptyList<String>(), policy.dueOnFix(listOf("a1"))) }
    }

    @Test
    fun alertSentWithoutAnyFixIsResentExactlyOnce() {
        val policy = AlertResendPolicy()
        policy.onSent("a1", AlertState.ALERT, hadFix = false)
        assertEquals(listOf("a1"), policy.dueOnFix(listOf("a1")))
        policy.onSent("a1", AlertState.ALERT, hadFix = true)
        assertEquals(emptyList<String>(), policy.dueOnFix(listOf("a1")))
    }

    @Test
    fun cancelledOrInactiveAlertsAreNotResent() {
        val policy = AlertResendPolicy()
        policy.onSent("a1", AlertState.ALERT, hadFix = false)
        policy.onSent("a1", AlertState.CANCEL, hadFix = false)
        assertEquals(emptyList<String>(), policy.dueOnFix(listOf("a1")))
        policy.onSent("a2", AlertState.ALERT, hadFix = false)
        assertEquals(emptyList<String>(), policy.dueOnFix(emptyList()))
        assertEquals(emptyList<String>(), policy.dueOnFix(listOf("a2")))
    }

    @Test
    fun repeatedSubmitOfTheSameAlertSendsOnce() = runBlocking {
        var sends = 0
        val forwarder = AlertForwarder(send = { sends++; true }, onChanged = { _, _ -> })
        val alert = forwarder.newAlert("Need water")
        assertTrue(forwarder.submit(alert, ready = true))
        assertTrue(forwarder.submit(alert, ready = true))
        assertEquals(1, sends)
    }

    @Test
    fun customAlertsAreSingleLineDeduplicatedAndCapped() {
        assertEquals("Need water now", CustomAlerts.normalize("  Need  water   now "))
        listOf("Need\nwater", "Need\rwater", "Need\twater", "\tNeed water", "Need water\n").forEach {
            assertNull(CustomAlerts.normalize(it))
            assertNotNull(CustomAlerts.validationError(emptyList(), it))
        }
        assertNull(CustomAlerts.normalize("   "))
        assertNull(CustomAlerts.normalize("x".repeat(CustomAlerts.MAX_LENGTH + 1)))
        assertNull(CustomAlerts.add(emptyList(), "911 alert"))
        assertEquals(listOf("Need water"), CustomAlerts.add(emptyList(), "Need water"))
        assertNull(CustomAlerts.add(listOf("Need water"), "NEED WATER"))
        assertNull(CustomAlerts.add(List(CustomAlerts.MAX_COUNT) { "a$it" }, "new"))
        assertEquals(listOf("b"), CustomAlerts.remove(listOf("a", "b"), "a"))
        assertEquals("Alert already exists", CustomAlerts.validationError(listOf("Need water"), "need water"))
        assertEquals("Please enter an alert", CustomAlerts.validationError(emptyList(), " "))
        assertNull(CustomAlerts.validationError(emptyList(), "Need water"))
        assertEquals("Type Custom Alert", CUSTOM_ALERT_HINT)
    }

    @Test
    fun customAlertsPersistThroughTheirEncoding() {
        val saved = listOf("Need water", "Ammo 'low' & \"fast\"")
        assertEquals(saved, CustomAlerts.decode(CustomAlerts.encode(saved)))
        assertEquals(emptyList<String>(), CustomAlerts.decode("not json"))
        assertEquals(listOf("A", "a", "  "), CustomAlerts.decode("""["A","a","  "]"""))
    }

    @Test
    fun newAlertsUseSixtyUtf16UnitsAndTwentyEntryLimit() {
        assertEquals("x".repeat(60), CustomAlerts.add(emptyList(), "x".repeat(60))!!.single())
        assertNull(CustomAlerts.add(emptyList(), "x".repeat(61)))
        assertEquals("\uD83D\uDE00".repeat(30), CustomAlerts.normalize("\uD83D\uDE00".repeat(30)))
        assertNull(CustomAlerts.normalize("\uD83D\uDE00".repeat(30) + "x"))
        assertEquals("Need water", CustomAlerts.normalize("\u00A0Need\u2003\u2003water\u00A0"))
        (('\u0000'..'\u001F') + ('\u007F'..'\u009F')).forEach {
            assertNull(CustomAlerts.normalize("Need${it}water"))
        }
        val nineteen = List(19) { "entry$it" }
        val twenty = CustomAlerts.add(nineteen, "last")!!
        assertEquals(20, twenty.size)
        assertNull(CustomAlerts.add(twenty, "too many"))
        assertNotNull(ProfilePresetParser.parse("""{"customAlerts":${CustomAlerts.encode(twenty)}}"""))
        assertNull(ProfilePresetParser.parse("""{"customAlerts":${CustomAlerts.encode(twenty + "too many")}}"""))
        assertNull(CustomAlerts.add(listOf("Need water"), "  NEED   water "))
    }

    @Test
    fun legacyRecordsAndBackupTextAreRetainedWithoutRevalidationOrTruncation() {
        val legacy = listOf("x".repeat(80), "old\ntext", "old\ttext") + List(21) { "entry$it" }
        assertEquals(legacy, CustomAlerts.decode(CustomAlerts.encode(legacy)))
        val backup = org.json.JSONObject(ProfilePresets.encode(TrackerConfig(customAlerts = legacy)))
            .getJSONArray("customAlerts")
        assertEquals(legacy, (0 until backup.length()).map { backup.getString(it) })
        assertEquals(legacy, CustomAlerts.merge(legacy, listOf("new")).first)
        assertNull(ProfilePresetParser.parse("""{"customAlerts":["new\ntext"]}"""))
        assertNull(ProfilePresetParser.parse("""{"customAlerts":["new\ttext"]}"""))
    }
    @Test
    fun presetsCarryCustomAlerts() {
        val json = ProfilePresets.encode(TrackerConfig(callsign = "ALPHA", customAlerts = listOf("Need water")))
        val preset = ProfilePresetParser.parse(json)
        assertNotNull(preset)
        assertEquals(listOf("Need water"), preset!!.customAlerts)
        assertEquals(listOf("Need water"), ProfilePresets.summarize("p", json).customAlerts)
        assertNull(ProfilePresetParser.parse("""{"customAlerts":["ok", 5]}"""))
        assertNull(ProfilePresetParser.parse("""{"customAlerts":"Need water"}"""))
        assertNull(ProfilePresetParser.parse("""{"customAlerts":["${"x".repeat(CustomAlerts.MAX_LENGTH + 1)}"]}"""))
        assertEquals(emptyList<String>(), ProfilePresetParser.parse("""{"profile":{}}""")!!.customAlerts)
        assertTrue(!ProfilePresets.encode(TrackerConfig()).contains("customAlerts"))
    }

    @Test
    fun loadProfileMergesCustomAlerts() = runBlocking {
        var saved = listOf("Need water")
        val handler = LoadProfileHandler(
            source = { """{"customAlerts":["need water","Ammo low"]}""" },
            profile = ProfileHandler(ProfileHandler.PROFILE_KEYS, ProfileStore { }),
            serverSink = { 0 },
            networkLocked = { false },
            customAlertSink = CustomAlertSink { alerts ->
                val (merged, added) = CustomAlerts.merge(saved, alerts)
                saved = merged
                added
            },
        )
        val result = handler.execute(
            ChatCommand("loadprofile", mapOf("name" to "ops")),
            ChatCommandRequest("u1", "local", "", null, 0, 0, "local"),
        )
        assertTrue(result.message, result.ok)
        assertTrue(result.message, result.message.endsWith("1 of 2 custom alerts added"))
        assertEquals(listOf("Need water", "Ammo low"), saved)
    }
}
