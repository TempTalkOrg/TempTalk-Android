package com.difft.android.network.auth

import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HeaderInterceptor
import com.difft.android.network.HiltEntryPointRule
import com.difft.android.network.NetworkException
import com.difft.android.network.TestScopeApplication
import okhttp3.Request
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HeaderInterceptor]'s fail-safe path: a request carrying no `Invocation` tag, and therefore no
 * [Auth] declaration, keeps the pre-declaration rule — a header already present wins, absent means
 * implicit Basic — except that a token header is now always the current stored token rather than
 * the caller's possibly stale or empty copy. The `AuthStyleMissing` lint rule keeps every Retrofit
 * endpoint out of this path; only a request built outside Retrofit can reach it (issue #1231).
 *
 * Mocked `Interceptor.Chain`, following `ProxyHostInterceptorTest`; Robolectric because the
 * interceptor reads the `application` global.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class)
class HeaderInterceptorUndeclaredTest {

    @get:Rule
    val hiltEntryPoints = HiltEntryPointRule()

    private fun basicProvider(value: String = BASIC) = object : ChativeHttpClient.AuthProvider {
        override fun provideAuth(): String = value
    }

    private fun interceptor(
        provider: ChativeHttpClient.AuthProvider? = basicProvider(),
        tokenProvider: FakeAuthTokenProvider = FakeAuthTokenProvider(),
    ) = HeaderInterceptor(
        authProvider = provider,
        tokenProvider = lazyOf<AuthTokenProvider>(tokenProvider),
        handleRejection = true,
        // The declaration is exactly what is missing here, so the debug guard has to be off.
        failFastOnMissingDeclaration = false,
    )

    private fun request(authorization: String? = null): Request =
        Request.Builder().url(BASE_URL).apply {
            authorization?.let { header("Authorization", it) }
        }.build()

    // T10 — a header-less, undeclared request still gets the implicit Basic credential.
    @Test
    fun `header-less request gets basic and never consults the token provider`() {
        val tokens = FakeAuthTokenProvider(token = JWT)
        val (chain, sent) = chainOf(request(), 200)

        interceptor(tokenProvider = tokens).intercept(chain)

        assertEquals(BASIC, sent.single.headers["Authorization"])
        assertEquals(0, tokens.currentCalls)
    }

    // T11 — a caller-supplied JWT is replaced by the current stored one.
    @Test
    fun `caller supplied token is replaced by the current token`() {
        val (chain, sent) = chainOf(request(authorization = "stale-token"), 200)

        interceptor(tokenProvider = FakeAuthTokenProvider(token = JWT)).intercept(chain)

        assertEquals(JWT, sent.single.headers["Authorization"])
    }

    // T12 — an EMPTY caller-supplied token header is replaced by the provider's token, never sent empty.
    @Test
    fun `empty token header is filled from the provider`() {
        val (chain, sent) = chainOf(request(authorization = ""), 200)

        interceptor(tokenProvider = FakeAuthTokenProvider(token = JWT)).intercept(chain)

        assertEquals(JWT, sent.single.headers["Authorization"])
    }

    // T13 — no credential at all: no header, and the terminal 401 keeps its caller contract.
    @Test
    fun `no credential sends no header and a 401 becomes NetworkException`() {
        val (chain, sent) = chainOf(request(authorization = ""), 401)

        val error = assertFailsWith<NetworkException> {
            interceptor(tokenProvider = FakeAuthTokenProvider(token = "")).intercept(chain)
        }

        assertNull(sent.first.headers["Authorization"])
        assertEquals(401, error.errorCode)
    }

    // T14 — a Basic 401 is not refreshable.
    @Test
    fun `a basic 401 is not refreshed`() {
        val tokens = FakeAuthTokenProvider(token = JWT)
        val (chain, sent) = chainOf(request(authorization = BASIC), 401)

        assertFailsWith<NetworkException> { interceptor(tokenProvider = tokens).intercept(chain) }

        assertEquals(0, tokens.refreshCalls)
        assertEquals(1, sent.size)
    }

    @Test
    fun `user agent and accept language are always added`() {
        val (chain, sent) = chainOf(request(), 200)

        interceptor().intercept(chain)

        assertTrue(sent.single.headers["User-Agent"]?.isNotEmpty() == true)
        assertTrue(sent.single.headers["Accept-Language"]?.isNotEmpty() == true)
    }

    @Test
    fun `an empty basic credential sends no header`() {
        val (chain, sent) = chainOf(request(), 200)

        interceptor(provider = basicProvider(value = "")).intercept(chain)

        assertNull(sent.single.headers["Authorization"])
    }

    private companion object {
        const val BASIC = "Basic dGVzdA=="
        const val JWT = "jwt-token"
    }
}
