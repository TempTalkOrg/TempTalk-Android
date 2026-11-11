package com.difft.android.app.startup

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertTrue










@RunWith(RobolectricTestRunner::class)
class LocalTokenVerifierTest {

    @Test
    fun `no basic credential fails the gate`() {
        assertFalse(LocalTokenVerifier.isLocalTokenValid(null, ACCOUNT, jwtFor(ACCOUNT)))
        assertFalse(LocalTokenVerifier.isLocalTokenValid("", ACCOUNT, jwtFor(ACCOUNT)))
    }

    @Test
    fun `a missing account fails the gate`() {
        assertFalse(LocalTokenVerifier.isLocalTokenValid(BASIC, null, jwtFor(ACCOUNT)))
        assertFalse(LocalTokenVerifier.isLocalTokenValid(BASIC, "", jwtFor(ACCOUNT)))
    }

    @Test
    fun `a missing token fails the gate`() {
        assertFalse(LocalTokenVerifier.isLocalTokenValid(BASIC, ACCOUNT, null))
        assertFalse(LocalTokenVerifier.isLocalTokenValid(BASIC, ACCOUNT, ""))
    }

    @Test
    fun `a token whose uid is the account passes the gate`() {
        assertTrue(LocalTokenVerifier.isLocalTokenValid(BASIC, ACCOUNT, jwtFor(ACCOUNT)))
    }

    @Test
    fun `a token belonging to another account fails the gate`() {
        assertFalse(LocalTokenVerifier.isLocalTokenValid(BASIC, ACCOUNT, jwtFor("+19998887777")))
    }

    @Test
    fun `an expired token still passes when the uid matches`() {
        
        
        assertTrue(
            LocalTokenVerifier.isLocalTokenValid(BASIC, ACCOUNT, jwtFor(ACCOUNT, expiresInMs = -60_000L))
        )
    }

    @Test
    fun `an unparsable token fails the gate without throwing`() {
        assertFalse(LocalTokenVerifier.isLocalTokenValid(BASIC, ACCOUNT, "garbage"))
    }

    private fun jwtFor(uid: String, expiresInMs: Long = 10 * 60_000L): String {
        val exp = (System.currentTimeMillis() + expiresInMs) / 1000
        val header = b64("""{"alg":"none","typ":"JWT"}""")
        val payload = b64("""{"exp":$exp,"uid":"$uid"}""")
        return "$header.$payload.sig"
    }

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private companion object {
        const val ACCOUNT = "+11112223333"
        const val BASIC = "Basic dGVzdA=="
    }
}
