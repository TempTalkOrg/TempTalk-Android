package com.difft.android.chat.fileshare

import com.difft.android.network.BaseResponse
import com.difft.android.network.NetworkException
import com.difft.android.network.auth.AuthStatus
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.network.auth.InvalidTokenException
import com.difft.android.network.auth.withInvalidTokenRetry
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The body-token retry the fileshare routes wrap their `Call.execute()` in: a rejected token is
 * refreshed and the block re-run exactly once, and nothing else is retried (issue #1235).
 *
 * Pure `runTest` — the provider is a hand-written fake and the block is a lambda over a counter, so
 * no Retrofit or OkHttp is involved.
 */
class InvalidTokenRetryTest {

    private class FakeProvider(
        private val first: String,
        private val refreshed: String,
    ) : AuthTokenProvider {
        var refreshCalls = 0
            private set

        override suspend fun current(): String = first
        override fun peek(): String = first
        override suspend fun refreshRejected(rejected: String): String {
            refreshCalls++
            return refreshed
        }
    }

    private fun ok(tag: String) = BaseResponse(ver = 1, status = 0, reason = null, data = tag)
    private fun rejected() = BaseResponse<String>(ver = 1, status = AuthStatus.INVALID_TOKEN, reason = null, data = null)

    // T15 — envelope status 5 then success: one retry, with the refreshed token.
    @Test
    fun `status 5 is refreshed and retried once`() = runTest {
        val provider = FakeProvider(first = STALE, refreshed = FRESH)
        val seen = mutableListOf<String>()

        val result = provider.withInvalidTokenRetry("isExist") { token ->
            seen += token
            if (seen.size == 1) rejected() else ok("second")
        }

        assertEquals(listOf(STALE, FRESH), seen)
        assertEquals("second", result.data)
        assertEquals(1, provider.refreshCalls)
    }

    // T16 — a bare HTTP 401 is the same rejection, arriving as an exception.
    @Test
    fun `a thrown 401 is refreshed and retried once`() = runTest {
        val provider = FakeProvider(first = STALE, refreshed = FRESH)
        val seen = mutableListOf<String>()

        val result = provider.withInvalidTokenRetry("download") { token ->
            seen += token
            if (seen.size == 1) throw NetworkException(401, "unauthorized") else ok("second")
        }

        assertEquals(listOf(STALE, FRESH), seen)
        assertEquals("second", result.data)
        assertEquals(1, provider.refreshCalls)
    }

    // T17 — rejected twice is terminal, with no third attempt.
    @Test
    fun `a persistent rejection throws after exactly two attempts`() = runTest {
        val provider = FakeProvider(first = STALE, refreshed = FRESH)
        var attempts = 0

        assertFailsWith<InvalidTokenException> {
            provider.withInvalidTokenRetry("uploadInfo") {
                attempts++
                rejected()
            }
        }

        assertEquals(2, attempts)
        assertEquals(1, provider.refreshCalls)
    }

    // T18 — without a fresh token there is nothing to retry with.
    @Test
    fun `no retry when the refresh yields no token`() = runTest {
        val provider = FakeProvider(first = STALE, refreshed = "")
        var attempts = 0

        assertFailsWith<InvalidTokenException> {
            provider.withInvalidTokenRetry("isExist") {
                attempts++
                rejected()
            }
        }

        assertEquals(1, attempts)
    }

    // T19 — a transport failure is not a token rejection.
    @Test
    fun `a non rejection failure propagates unretried`() = runTest {
        val provider = FakeProvider(first = STALE, refreshed = FRESH)
        var attempts = 0

        val error = assertFailsWith<IOException> {
            provider.withInvalidTokenRetry<String>("isExist") {
                attempts++
                throw IOException("socket closed")
            }
        }

        assertEquals("socket closed", error.message)
        assertEquals(1, attempts)
        assertEquals(0, provider.refreshCalls)
    }

    // T20 — only status 5 means "the token you sent is not usable".
    @Test
    fun `another business status is returned as is`() = runTest {
        val provider = FakeProvider(first = STALE, refreshed = FRESH)
        var attempts = 0

        val result = provider.withInvalidTokenRetry("isExist") {
            attempts++
            BaseResponse<String>(ver = 1, status = NO_PERMISSION, reason = "expired", data = null)
        }

        assertEquals(NO_PERMISSION, result.status)
        assertEquals(1, attempts)
        assertEquals(0, provider.refreshCalls)
    }

    private companion object {
        const val STALE = "stale-token"
        const val FRESH = "fresh-token"
        const val NO_PERMISSION = 2
    }
}
