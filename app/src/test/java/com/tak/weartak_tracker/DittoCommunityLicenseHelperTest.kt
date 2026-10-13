package com.tak.weartak_tracker

import com.tak.weartak_tracker.service.DittoCommunityLicenseHelper
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DittoCommunityLicenseHelperTest {
    @Test
    fun parsesSharedKeyCommunityPdl() {
        val license = DittoCommunityLicenseHelper.parse(pdl())

        assertEquals("test-app", license.appId)
        assertEquals("test-shared-key", license.sharedKey)
        assertEquals("test-license", license.licenseToken)
        assertEquals(4_000_000_000_000L, license.expiresAtMillis)
    }

    @Test
    fun rejectsUnsupportedIdentityType() {
        val invalidPdl = pdl(identityType = "OTHER")

        assertThrows(IllegalArgumentException::class.java) {
            DittoCommunityLicenseHelper.parse(invalidPdl)
        }
    }

    private fun pdl(identityType: String = "SK"): String {
        val json = """{"v":2,"it":"$identityType","a":"test-app","s":"test-shared-key","t":"test-license","e":4000000000000}"""
        val compressed = ByteArrayOutputStream().use { output ->
            DeflaterOutputStream(output).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            output.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }
}
