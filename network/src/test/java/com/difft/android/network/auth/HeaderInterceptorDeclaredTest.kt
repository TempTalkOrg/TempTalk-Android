package com.difft.android.network.auth

import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HeaderInterceptor
import com.difft.android.network.HiltEntryPointRule
import com.difft.android.network.NetworkException
import com.difft.android.network.TestScopeApplication
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HeaderInterceptor] resolving each endpoint's own [Auth] declaration — the one thing that decides
 * which `Authorization` goes on the wire (issues #1235, #1231).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class)
class HeaderInterceptorDeclaredTest {

    @get:Rule
    val hiltEntryPoints = HiltEntryPointRule()

    private val basicProvider = object : ChativeHttpClient.AuthProvider {
        override fun provideAuth(): String = BASIC
    }

    private fun interceptor(
        tokenProvider: FakeAuthTokenProvider = FakeAuthTokenProvider(),
        handleRejection: Boolean = true,
        failFast: Boolean = false,
    ) = HeaderInterceptor(
        authProvider = basicProvider,
        tokenProvider = lazyOf<AuthTokenProvider>(tokenProvider),
        handleRejection = handleRejection,
        failFastOnMissingDeclaration = failFast,
    )

    // T28 — a NONE endpoint strips Authorization and keeps Content-Type.
    @Test
    fun `none strips authorization and preserves content type`() {
        val request = requestOf(NoAuthService::class.java) { it.strip() }
            .newBuilder()
            .header("Authorization", "stale")
            .header("Content-Type", "application/json")
            .build()
        val (chain, sent) = chainOf(request, 200)

        interceptor().intercept(chain)

        assertNull(sent.single.headers["Authorization"])
        assertEquals("application/json", sent.single.headers["Content-Type"])
    }

    // T29 — a JWT endpoint carries the token and never the Basic credential.
    @Test
    fun `jwt sends the token and never basic`() {
        val (chain, sent) = chainOf(requestOf(JwtService::class.java) { it.token() }, 200)

        interceptor(FakeAuthTokenProvider(token = JWT)).intercept(chain)

        assertEquals(JWT, sent.single.headers["Authorization"])
        assertFalse(sent.single.headers["Authorization"]!!.contains("Basic"))
    }

    // T30 — an EXPLICIT endpoint keeps the header its caller built (the login pre-persist window).
    @Test
    fun `explicit leaves the caller header untouched`() {
        val tokens = FakeAuthTokenProvider(token = JWT)
        val request = requestOf(ExplicitService::class.java) { it.explicit() }
            .newBuilder()
            .header("Authorization", "Basic xyz")
            .build()
        val (chain, sent) = chainOf(request, 200)

        interceptor(tokens).intercept(chain)

        assertEquals("Basic xyz", sent.single.headers["Authorization"])
        assertEquals(0, tokens.currentCalls)
        assertEquals(0, tokens.refreshCalls)
    }

    // T31 — a JWT 401 refreshes once and retries the SAME url that answered.
    @Test
    fun `a jwt 401 refreshes once and retries the same url`() {
        val tokens = FakeAuthTokenProvider(token = STALE, refreshedTo = FRESH)
        val request = requestOf(JwtService::class.java) { it.token() }
        val (chain, sent) = chainOf(request, 401, 200)

        val response = interceptor(tokens).intercept(chain)

        assertEquals(200, response.code)
        assertEquals(2, sent.size)
        assertEquals(STALE, sent.all[0].headers["Authorization"])
        assertEquals(FRESH, sent.all[1].headers["Authorization"])
        assertEquals(sent.all[0].url, sent.all[1].url)
        assertEquals(STALE, tokens.lastRejected)
        assertEquals(1, tokens.refreshCalls)
        // The rejected response is released; the one handed back to the caller is not.
        assertTrue(sent.bodies[0].closed)
        assertFalse(sent.bodies[1].closed)
    }

    // T32 — a second 401 is terminal: one retry, one refresh.
    @Test
    fun `a second 401 throws after exactly one retry`() {
        val tokens = FakeAuthTokenProvider(token = STALE, refreshedTo = FRESH)
        val (chain, sent) = chainOf(requestOf(JwtService::class.java) { it.token() }, 401, 401)

        val error = assertFailsWith<NetworkException> { interceptor(tokens).intercept(chain) }

        assertEquals(401, error.errorCode)
        assertEquals(2, sent.size)
        assertEquals(1, tokens.refreshCalls)
        assertTrue(sent.bodies.all { it.closed })
    }

    // T33 — the Signal-API contract: a raw 401 is returned, not converted, and not refreshed.
    @Test
    fun `without rejection handling a 401 is returned raw`() {
        val tokens = FakeAuthTokenProvider(token = JWT, refreshedTo = FRESH)
        val (chain, sent) = chainOf(requestOf(BasicDeclaringService::class.java) { it.inheritsInterface() }, 401)

        val response = interceptor(tokens, handleRejection = false).intercept(chain)

        assertEquals(401, response.code)
        assertEquals(1, sent.size)
        assertEquals(0, tokens.refreshCalls)
    }

    // T34 — a one-shot body cannot be replayed, so no retry is attempted.
    @Test
    fun `a one-shot body is not retried`() {
        val tokens = FakeAuthTokenProvider(token = STALE, refreshedTo = FRESH)
        val request = requestOf(JwtService::class.java) { it.token() }
            .newBuilder()
            .method("POST", oneShotBody())
            .build()
        val (chain, sent) = chainOf(request, 401)

        assertFailsWith<NetworkException> { interceptor(tokens).intercept(chain) }

        assertEquals(1, sent.size)
        assertEquals(0, tokens.refreshCalls)
        assertTrue(sent.bodies.single().closed)
    }

    // T35 — an undeclared endpoint falls back to implicit basic instead of losing its credential.
    @Test
    fun `an undeclared endpoint falls back to implicit basic`() {
        val (chain, sent) = chainOf(requestOf(UndeclaredService::class.java) { it.undeclared() }, 200)

        interceptor().intercept(chain)

        assertEquals(BASIC, sent.single.headers["Authorization"])
    }

    @Test
    fun `an undeclared endpoint fails fast when the debug guard is armed`() {
        val (chain, _) = chainOf(requestOf(UndeclaredService::class.java) { it.undeclared() }, 200)

        assertFailsWith<IllegalStateException> { interceptor(failFast = true).intercept(chain) }
    }

    // A refresh that returns the same token is not worth a retry.
    @Test
    fun `no retry when the refresh returns the same token`() {
        val tokens = FakeAuthTokenProvider(token = STALE, refreshedTo = STALE)
        val (chain, sent) = chainOf(requestOf(JwtService::class.java) { it.token() }, 401)

        assertFailsWith<NetworkException> { interceptor(tokens).intercept(chain) }

        assertEquals(1, sent.size)
    }

    // T49 — a declared BASIC endpoint's 401 is terminal: nothing is refreshed, nothing is retried,
    // and the rejected response is closed rather than leaked.
    @Test
    fun `a declared basic 401 is not refreshed and its response is closed`() {
        val tokens = FakeAuthTokenProvider(token = JWT, refreshedTo = FRESH)
        val request = requestOf(BasicDeclaringService::class.java) { it.inheritsInterface() }
        val (chain, sent) = chainOf(request, 401)

        val error = assertFailsWith<NetworkException> { interceptor(tokens).intercept(chain) }

        assertEquals(401, error.errorCode)
        assertEquals(1, sent.size)
        assertEquals(BASIC, sent.single.headers["Authorization"])
        assertEquals(0, tokens.refreshCalls)
        assertTrue(sent.bodies.single().closed)
    }

    private fun oneShotBody(): RequestBody = object : RequestBody() {
        override fun contentType() = "text/plain".toMediaType()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            sink.writeUtf8("once")
        }
    }

    private companion object {
        const val BASIC = "Basic dGVzdA=="
        const val JWT = "jwt-token"
        const val STALE = "stale-token"
        const val FRESH = "fresh-token"
    }
}
