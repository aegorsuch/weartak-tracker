package com.tak.weartak_tracker

import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.cot.Physio
import com.tak.weartak_tracker.data.MedicalProfile
import com.tak.weartak_tracker.data.MedicalProfileCodec
import com.tak.weartak_tracker.data.TrackerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class BatdokTest {
    private fun pli(physio: Physio? = null, enabled: Boolean = true, age: Int? = null) =
        CotBuilder.pli("u1", "cs", "Cyan", "HQ", 50, null, CotTime.now(30, 0), physio, enabled, age)

    @Test
    fun profileRoundTripsAndStartsUnknown() {
        assertTrue(TrackerConfig().batdokEnabled)
        assertFalse(TrackerConfig().physioMonitoring)
        assertEquals(MedicalProfile(), MedicalProfileCodec.decode(null))
        assertEquals(MedicalProfile(), MedicalProfileCodec.decode(MedicalProfileCodec.encode(MedicalProfile())))
        val profile = MedicalProfile(1990, 5, 11, 180, "Male", "O-", setOf("Aspirin", "Sulfa Drugs"), "Coalition Civilian")
        assertEquals(profile, MedicalProfileCodec.decode(MedicalProfileCodec.encode(profile)))
        assertEquals(36, profile.ageYears(2026))
        assertNull(MedicalProfile().ageYears(2026))
        assertNull(profile.copy(birthYear = 2027).ageYears(2026))
    }

    @Test
    fun toggleGatesOnlyBatdokVitalSigns() {
        val off = pli(Physio(72, 98.6f), enabled = false, age = 36)
        assertTrue(off.contains("HR:72;SkinTemp:98.6"))
        assertFalse(off.contains("_atmist_"))
        assertTrue(off.contains("<biometrics>"))
        assertTrue(off.contains("<hr>72</hr>"))
        assertTrue(off.contains("<skt>98.6</skt>"))
        assertFalse(pli().contains("_atmist_"))
        assertFalse(pli().contains("<biometrics>"))
    }

    @Test
    fun bothVitalSignsHaveCorrectUnitsAgeAndObservedTime() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(pli(Physio(72, 98.6f), age = 36).byteInputStream())
        val atmist = doc.getElementsByTagName("_atmist_").item(0)
        assertEquals("36", atmist.attributes.getNamedItem("age").nodeValue)
        val vitals = doc.getElementsByTagName("vitalSign")
        assertEquals(2, vitals.length)
        assertEquals("(HR,72,0)", vitals.item(0).textContent)
        assertEquals("(Temp,98.6 (F) 37 (C),0)", vitals.item(1).textContent)
        for (index in 0 until vitals.length) {
            assertEquals(index.toString(), vitals.item(index).attributes.getNamedItem("index").nodeValue)
            assertEquals("1970-01-01T00:00:00.000Z", vitals.item(index).attributes.getNamedItem("timestamp").nodeValue)
        }
    }

    @Test
    fun missingReadingsAreOmittedAndSingleReadingStartsAtZero() {
        assertFalse(pli(Physio(-1, Float.NaN)).contains("_atmist_"))
        assertFalse(pli(Physio(null, Float.POSITIVE_INFINITY)).contains("_atmist_"))
        assertFalse(pli(Physio(null, -1f)).contains("_atmist_"))
        val hrOnly = pli(Physio(72))
        assertTrue(hrOnly.contains("(HR,72,0)"))
        assertFalse(hrOnly.contains("(Temp,"))
        assertFalse(hrOnly.contains(" age="))
        val skinOnly = pli(Physio(null, 98.6f))
        assertTrue(skinOnly.contains("<vitalSign index='0'"))
        assertFalse(skinOnly.contains("(HR,"))
    }

    @Test
    fun decimalFormattingIsLocaleIndependent() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertTrue(pli(Physio(72, 98.6f)).contains("(Temp,98.6 (F) 37 (C),0)"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
