package com.tak.weartak_tracker

import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.cot.Physio
import com.tak.weartak_tracker.cot.calculateExertion
import com.tak.weartak_tracker.data.MedicalProfile
import com.tak.weartak_tracker.data.MedicalProfileCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExertionTest {
    @Test
    fun matchesWearTakKarvonenCalculation() {
        assertEquals(0.5, calculateExertion(120, 40, 60)!!, 0.000001)
        assertEquals(0.0, calculateExertion(50, 40, 60)!!, 0.000001)
        assertEquals(1.0, calculateExertion(180, 40, 60)!!, 0.000001)
        assertEquals(1.25, calculateExertion(210, 40, 60)!!, 0.000001)
        assertEquals(0.4, calculateExertion(120, 40, 80)!!, 0.000001)
    }

    @Test
    fun missingOrInvalidInputsAreUnknown() {
        assertNull(calculateExertion(null, 40, 60))
        assertNull(calculateExertion(-1, 40, 60))
        assertNull(calculateExertion(0, 40, 60))
        assertNull(calculateExertion(120, null, 60))
        assertNull(calculateExertion(120, -1, 60))
        assertNull(calculateExertion(120, 40, 0))
        assertNull(calculateExertion(120, 40, 180))
    }

    @Test
    fun profilePreservesRestingHeartRateAndDefaultsForOlderProfiles() {
        assertEquals(60, MedicalProfileCodec.decode("{}").restingHeartRateBpm)
        val profile = MedicalProfile(birthYear = 1986, restingHeartRateBpm = 80)
        assertEquals(profile, MedicalProfileCodec.decode(MedicalProfileCodec.encode(profile)))
    }

    @Test
    fun cotUsesCivFractionAndRespectsBatdokToggle() {
        fun pli(enabled: Boolean, physio: Physio) =
            CotBuilder.pli("u", "cs", "Cyan", "HQ", 50, null, CotTime.now(30, 0), physio, enabled)
        val physio = Physio(120, exertion = calculateExertion(120, 40, 60))
        val on = pli(true, physio)
        assertTrue(on.contains("Exert:0.50%;"))
        assertTrue(on.contains("<exert>0.50</exert>"))
        val off = pli(false, physio)
        assertTrue(off.contains("Exert:0.50%;"))
        assertTrue(off.contains("<biometrics>"))
        assertTrue(off.contains("<exert>0.50</exert>"))
        assertFalse(off.contains("_atmist_"))
        assertTrue(pli(true, Physio(-1, exertion = 0.5)).contains("<exert>N/A</exert>"))
        assertTrue(pli(true, Physio(120, exertion = Double.NaN)).contains("<exert>N/A</exert>"))
    }
}
