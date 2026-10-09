package com.tak.weartak_tracker

import com.tak.weartak_tracker.data.SitxAccountSnapshot
import com.tak.weartak_tracker.data.decodeSitxAccount
import com.tak.weartak_tracker.data.decodeSitxAccountSnapshot
import com.tak.weartak_tracker.data.formatSitxPairingCode
import com.tak.weartak_tracker.data.isValidSitxReauthPin
import com.tak.weartak_tracker.data.sitxAccountValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SitxIdentityTest {
    @Test
    fun formatsOnlyEightAlphanumericPairingCodes() {
        assertEquals("ABCD-EFGH", formatSitxPairingCode("ABCDEFGH"))
        assertEquals("ABCD-EFGH", formatSitxPairingCode("abcdEfgh"))
        assertEquals("A1B2-C3D4", formatSitxPairingCode("a1b2c3d4"))
        assertEquals("ABC-DEF", formatSitxPairingCode("ABC-DEF"))
        assertEquals("ABCDEFG", formatSitxPairingCode("ABCDEFG"))
        assertEquals("ABCDEFGHI", formatSitxPairingCode("ABCDEFGHI"))
    }

    @Test
    fun decodesPersonEmailAndCallsignFallback() {
        assertEquals("alex@weartak.com", decodeSitxAccount(token(user_email = "alex@weartak.com", access_type = "user"))?.label)
        assertEquals("ALPHA-01", decodeSitxAccount(token(callsign = "ALPHA-01", access_type = "user"))?.label)
    }

    @Test
    fun identifiesNpeByEmailWithoutAtEvenForUserAccessType() {
        val account = decodeSitxAccount(
            token(user_email = "odin-weartak-iosimulator", callsign = "IGNORED", access_type = "user"),
        )
        assertEquals("odin-weartak-iosimulator", account?.label)
        assertTrue(account?.isNpe == true)
    }

    @Test
    fun identifiesOtherAccessTypesAsNpeAndUsesNpeFallbackName() {
        val named = decodeSitxAccount(token(callsign = "SENSOR-01", access_type = "sensor"))
        assertEquals("SENSOR-01", named?.label)
        assertTrue(named?.isNpe == true)
        assertEquals("NPE", decodeSitxAccount(token(access_type = "sensor"))?.label)
    }

    @Test
    fun userTokenWithoutIdentityClaimsHasNoDecodedAccount() {
        assertNull(decodeSitxAccount(token(access_type = "user")))
        assertNull(decodeSitxAccount(token()))
    }

    @Test
    fun noAuthorizationShowsLocalizedFallbackValue() {
        assertEquals(
            "Not authorized",
            sitxAccountValue(authorized = false, account = null, notAuthorized = "Not authorized"),
        )
        assertEquals("", sitxAccountValue(authorized = true, account = null, notAuthorized = "Not authorized"))
        assertNull(decodeSitxAccount(null))
        assertNull(decodeSitxAccount("malformed"))
    }

    @Test
    fun reauthPinAcceptsExactlySixAsciiDigits() {
        assertTrue(isValidSitxReauthPin("123456"))
        assertFalse(isValidSitxReauthPin("12345"))
        assertFalse(isValidSitxReauthPin("1234567"))
        assertFalse(isValidSitxReauthPin("12٣456"))
        assertFalse(isValidSitxReauthPin("12 456"))
    }

    @Test
    fun olderSettingsSnapshotKeepsMissingNpeFlagNull() {
        assertEquals(
            SitxAccountSnapshot(account = "alex@example.com", isNpe = null),
            decodeSitxAccountSnapshot("""{"sitxAccount":"alex@example.com"}"""),
        )
    }

    private fun token(
        user_email: String? = null,
        callsign: String? = null,
        access_type: String? = null,
    ): String {
        val claims = listOfNotNull(
            user_email?.let { "\"user_email\":\"$it\"" },
            callsign?.let { "\"callsign\":\"$it\"" },
            access_type?.let { "\"access_type\":\"$it\"" },
        ).joinToString(",")
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{$claims}".toByteArray())
        return "header.$payload.signature"
    }
}
