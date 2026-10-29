package com.tak.weartak_tracker

import com.tak.weartak_tracker.cot.CotBuilder
import com.tak.weartak_tracker.cot.CotTime
import com.tak.weartak_tracker.cot.Physio
import com.tak.weartak_tracker.data.MoppStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class MoppPliTest {
    private fun pli(status: MoppStatus, physio: Physio? = null, batdok: Boolean = true) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            CotBuilder.pli(
                "u1", "cs", "Cyan", "HQ", 50, null, CotTime.now(30, 0),
                physio = physio, includeBatdok = batdok, moppStatus = status,
            ).byteInputStream(),
        )

    @Test
    fun noneOmitsRemarksButZeroThroughFourPublishSavedLevelWithoutFabricatedPhysio() {
        assertEquals(0, pli(MoppStatus.NONE).getElementsByTagName("remarks").length)
        for ((level, status) in MoppStatus.entries.filter { it != MoppStatus.NONE }.withIndex()) {
            val doc = pli(status)
            val remarks = doc.getElementsByTagName("remarks")
            assertEquals(1, remarks.length)
            assertEquals("MOPP$level", remarks.item(0).textContent)
            assertEquals(0, doc.getElementsByTagName("physiology").length)
            assertEquals(0, doc.getElementsByTagName("biometrics").length)
            assertEquals(0, doc.getElementsByTagName("_atmist_").length)
            assertFalse(remarks.item(0).textContent.contains("WearTAK PLI"))
            assertEquals("WearTAK-Tracker", doc.getElementsByTagName("takv").item(0).attributes.getNamedItem("platform").nodeValue)
        }
    }

    @Test
    fun ppeAndPhysioShareOneRemarksElementAndPreservePhysiologyAndBatdokToggle() {
        for (batdok in listOf(false, true)) {
            for (status in MoppStatus.entries) {
                val doc = pli(status, Physio(72, 98.6f, 0.5), batdok)
                val remarks = doc.getElementsByTagName("remarks")
                assertEquals(1, remarks.length)
                val expected = "Exert:0.50%;HR:72;SkinTemp:98.6" +
                    if (status == MoppStatus.NONE) "" else "\nMOPP${status.ordinal - 1}"
                assertEquals(expected, remarks.item(0).textContent)
                val physiology = doc.getElementsByTagName("physiology")
                assertEquals(1, physiology.length)
                assertEquals("detail", physiology.item(0).parentNode.nodeName)
                assertEquals(0, doc.getElementsByTagName("biometrics").length)
                assertEquals("72", doc.getElementsByTagName("hr").item(0).textContent)
                assertEquals("98.6", doc.getElementsByTagName("skt").item(0).textContent)
                assertEquals("0.50", doc.getElementsByTagName("exert").item(0).textContent)
                assertEquals(if (batdok) 1 else 0, doc.getElementsByTagName("_atmist_").length)
            }
        }
    }
}
