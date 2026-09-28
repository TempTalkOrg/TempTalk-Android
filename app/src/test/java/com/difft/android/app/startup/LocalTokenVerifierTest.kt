package com.difft.android.app.startup

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cold-start identity gate (issue #758): a stored Basic credential is trusted only when the
 * stored JWT's `uid` claim is the stored account. Robolectric because the gate decodes the token
 * with `com.auth0.android.jwt.JWT`, which reads its claims through `android.util.Base64` — a stub
 * that throws "not mocked" on a plain JVM.
 *
 * T24 — including the row where the gate must fail closed rather than throw: an unparsable token
 * (issue #1231).
 */
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
        // The gate binds identity, not freshness: expiry is the token provider's business, which
        // is why peek() must not apply the refresh margin.
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
