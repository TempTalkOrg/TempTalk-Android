package com.difft.android.network.auth

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.responses.AuthToken
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AuthTokenManager] read-through, expiry margin, refresh de-duplication and failure cooldown
 * (issue #1231). Robolectric because the expiry test decodes the token with
 * `com.auth0.android.jwt.JWT`, which reads its claims through `android.util.Base64` — a stub that
 * throws "not mocked" on a plain JVM.
 *
 * `UserManager` is a hand-written fake rather than a mock: the post-lock re-check depends on
 * `update {}` mutating the snapshot SYNCHRONOUSLY, and a relaxed mock that dropped the write would
 * make the de-duplication tests pass for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
class AuthTokenManagerTest {

    private val httpService = mockk<HttpService>()
    private val authClient = mockk<ChativeHttpClient>()
    private val fetchCount = AtomicInteger(0)

    /** The refreshed token must itself be usable, or every post-lock re-check refreshes again. */
    private val freshToken = testJwt(expiresInMs = 10 * 60_000L)

    private class FakeUserManager(private var data: UserData?) : UserManager {
        override fun setUserData(userData: UserData, commit: Boolean) {
            data = userData
        }

        override fun getUserData(): UserData? = data
    }

    private fun userManager(baseAuth: String? = "Basic abc", microToken: String? = null) =
        FakeUserManager(UserData().apply {
            this.baseAuth = baseAuth
            this.microToken = microToken
        })

    private fun manager(userManager: UserManager) =
        AuthTokenManager(authClient, userManager)

    /** Both clocks pinned to [now], so a token's exact expiry instant is comparable. */
    private fun managerAt(now: Long, userManager: UserManager): AuthTokenManager {
        ServerTimeProvider.resetForTest(wallClock = { now }, elapsedClock = { 0L })
        return manager(userManager).apply { nowMs = { now } }
    }

    @Before
    fun setUp() {
        // Anchor-free deterministic clock: nowMillis() == wall clock, so token expiry maths in the
        // test is relative to System.currentTimeMillis() exactly as testJwt builds it.
        ServerTimeProvider.resetForTest(wallClock = { System.currentTimeMillis() }, elapsedClock = { 0L })
        every { authClient.httpService } returns httpService
        fetchCount.set(0)
    }

    @After
    fun tearDown() {
        clearMocks(httpService, authClient)
    }

    private fun stubRefresh(newToken: String = freshToken) {
        coEvery { httpService.fetchAuthToken(any()) } coAnswers {
            fetchCount.incrementAndGet()
            BaseResponse(ver = 1, status = 0, reason = null, data = AuthToken(newToken))
        }
    }

    // T1 — cold start with a valid stored token serves it without a refresh.
    @Test
    fun `current returns a stored token that is not expiring`() = runTest {
        stubRefresh()
        val stored = testJwt(expiresInMs = 10 * 60_000L)

        val token = manager(userManager(microToken = stored)).current()

        assertEquals(stored, token)
        assertEquals(0, fetchCount.get())
    }

    // T2 — a token inside the 120 s margin refreshes.
    @Test
    fun `current refreshes a token inside the expiry margin`() = runTest {
        stubRefresh()
        val users = userManager(microToken = testJwt(expiresInMs = 60_000L))

        val token = manager(users).current()

        assertEquals(freshToken, token)
        assertEquals(1, fetchCount.get())
        assertEquals(freshToken, users.getUserData()?.microToken)
    }

    // T3 — just outside the margin is still served from storage.
    @Test
    fun `current keeps a token just outside the expiry margin`() = runTest {
        stubRefresh()
        val stored = testJwt(expiresInMs = 121_000L)

        val token = manager(userManager(microToken = stored)).current()

        assertEquals(stored, token)
        assertEquals(0, fetchCount.get())
    }

    // T4 — no credential means no token and no refresh, even with a valid stored JWT.
    @Test
    fun `current returns empty when there is no credential`() = runTest {
        stubRefresh()
        val users = userManager(baseAuth = null, microToken = testJwt(expiresInMs = 10 * 60_000L))

        assertEquals("", manager(users).current())
        assertEquals(0, fetchCount.get())
    }

    // T5 — before storage warm-up getUserData() is null; no throw, no refresh.
    @Test
    fun `current returns empty before storage warm up`() = runTest {
        stubRefresh()

        assertEquals("", manager(FakeUserManager(null)).current())
        assertEquals(0, fetchCount.get())
    }

    // T6 — an unparsable stored token counts as expiring and is replaced.
    @Test
    fun `current refreshes an unparsable stored token`() = runTest {
        stubRefresh()

        val token = manager(userManager(microToken = "not-a-jwt")).current()

        assertEquals(freshToken, token)
        assertEquals(1, fetchCount.get())
    }

    // T7 — concurrent current() on an expired token collapse onto one refresh.
    @Test
    fun `concurrent current calls issue one refresh`() = runTest {
        stubRefresh()
        val subject = manager(userManager(microToken = testJwt(expiresInMs = -60_000L)))

        val tokens = List(CONCURRENT) { async { subject.current() } }.awaitAll()

        assertEquals(1, fetchCount.get())
        assertTrue(tokens.all { it == freshToken })
    }

    // T8 — a rejection burst against one stale token collapses onto one refresh.
    @Test
    fun `concurrent refreshRejected calls for the same token issue one refresh`() = runTest {
        stubRefresh()
        // Distinct from freshToken: the collapse is keyed on the rejected VALUE.
        val stale = testJwt(expiresInMs = 9 * 60_000L, uid = "stale")
        val subject = manager(userManager(microToken = stale))

        val tokens = List(CONCURRENT) { async { subject.refreshRejected(stale) } }.awaitAll()

        assertEquals(1, fetchCount.get())
        assertTrue(tokens.all { it == freshToken })
    }

    // T9 — a failing refresh is not amplified: one attempt per cooldown window.
    @Test
    fun `a failed refresh is suppressed for the cooldown window`() = runTest {
        coEvery { httpService.fetchAuthToken(any()) } coAnswers {
            fetchCount.incrementAndGet()
            throw IOException("boom")
        }
        val stale = testJwt(expiresInMs = -60_000L, uid = "stale")
        val subject = manager(userManager(microToken = stale))
        var fakeNow = 100_000L
        subject.nowMs = { fakeNow }

        assertEquals("", subject.refreshRejected(stale))
        // Inside the cooldown no new attempt is made, and the rejected token is never handed back.
        repeat(3) { assertEquals("", subject.refreshRejected(stale)) }
        assertEquals(1, fetchCount.get())

        fakeNow += AuthTokenManager.REFRESH_FAILURE_COOLDOWN_MS + 1
        subject.refreshRejected(stale)

        assertEquals(2, fetchCount.get())
    }

    // T39 — the refresh must not ride the capped @Chat dispatcher.
    @Test
    fun `the refresh client parameter is qualified with AuthToken`() {
        val annotations = AuthTokenManager::class.java.constructors.single().parameterAnnotations
        val clientAnnotations = annotations.first().map { it.annotationClass }

        assertTrue(clientAnnotations.contains(ChativeHttpClientModule.AuthToken::class))
    }

    // Empty result from the server is a failure, not a token.
    @Test
    fun `current returns empty when the server answers with no token`() = runTest {
        coEvery { httpService.fetchAuthToken(any()) } coAnswers {
            fetchCount.incrementAndGet()
            BaseResponse(ver = 1, status = 0, reason = null, data = AuthToken(""))
        }
        val users = userManager(microToken = testJwt(expiresInMs = -60_000L))

        assertEquals("", manager(users).current())
        coVerify(exactly = 1) { httpService.fetchAuthToken(any()) }
    }

    // T53 — a refresh that fails inside the expiry margin hands out the still-valid stored token,
    // never "" (an unauthenticated request); the failure still arms the cooldown.
    @Test
    fun `a failed refresh inside the margin keeps the still-valid stored token`() = runTest {
        coEvery { httpService.fetchAuthToken(any()) } coAnswers {
            fetchCount.incrementAndGet()
            throw IOException("boom")
        }
        val stillValid = testJwt(expiresInMs = 60_000L)
        val subject = manager(userManager(microToken = stillValid))

        assertEquals(stillValid, subject.current())
        assertEquals(1, fetchCount.get())
        // Second call inside the cooldown: no new attempt, same still-valid token.
        assertEquals(stillValid, subject.current())
        assertEquals(1, fetchCount.get())
    }

    // T54 — same for a server that answers with no token.
    @Test
    fun `an empty server token inside the margin keeps the still-valid stored token`() = runTest {
        coEvery { httpService.fetchAuthToken(any()) } coAnswers {
            fetchCount.incrementAndGet()
            BaseResponse(ver = 1, status = 0, reason = null, data = AuthToken(""))
        }
        val stillValid = testJwt(expiresInMs = 60_000L)

        assertEquals(stillValid, manager(userManager(microToken = stillValid)).current())
        assertEquals(1, fetchCount.get())
    }

    // T55 — refreshRejected never hands back the token the server just rejected, even when a
    // failed refresh would otherwise fall back to it (it is still valid by the clock).
    @Test
    fun `a failed forced refresh does not return the rejected token`() = runTest {
        coEvery { httpService.fetchAuthToken(any()) } coAnswers {
            fetchCount.incrementAndGet()
            throw IOException("boom")
        }
        val rejectedButUnexpired = testJwt(expiresInMs = 5 * 60_000L, uid = "rejected")
        val subject = manager(userManager(microToken = rejectedButUnexpired))

        assertEquals("", subject.refreshRejected(rejectedButUnexpired))
        assertEquals(1, fetchCount.get())
    }

    // T44 — the margin boundary is exclusive: exactly the margin left is still usable.
    @Test
    fun `current keeps a token with exactly the expiry margin left`() = runTest {
        stubRefresh()
        val stored = testJwtExpiringAt(FIXED_NOW + AuthTokenManager.TOKEN_EXPIRY_MARGIN_MS)
        val users = userManager(microToken = stored)

        assertEquals(stored, managerAt(FIXED_NOW, users).current())
        assertEquals(0, fetchCount.get())
    }

    // T45 — one ms inside the margin refreshes. `exp` has second resolution, so the token lands on
    // the whole second below the boundary — still the first instant that counts as expiring.
    @Test
    fun `current refreshes a token one ms inside the expiry margin`() = runTest {
        stubRefresh()
        val stored = testJwtExpiringAt(FIXED_NOW + AuthTokenManager.TOKEN_EXPIRY_MARGIN_MS - 1)
        val users = userManager(microToken = stored)

        assertEquals(freshToken, managerAt(FIXED_NOW, users).current())
        assertEquals(1, fetchCount.get())
    }

    // T46 — a rejected token storage has already replaced is answered from storage, not refreshed.
    @Test
    fun `refreshRejected returns the stored token when it is already newer`() = runTest {
        stubRefresh()
        val stored = testJwt(expiresInMs = 9 * 60_000L, uid = "newer")
        val users = userManager(microToken = stored)

        assertEquals(stored, manager(users).refreshRejected("stale"))
        assertEquals(0, fetchCount.get())
    }

    // T47 — no credential means nothing to refresh with, even for a rejection.
    @Test
    fun `refreshRejected returns empty when there is no credential`() = runTest {
        stubRefresh()
        val users = userManager(baseAuth = "", microToken = testJwt(expiresInMs = -60_000L))

        assertEquals("", manager(users).refreshRejected("stale"))
        assertEquals(0, fetchCount.get())
    }

    // T48 — logout lands while the refresh is in flight: the token is discarded, not written back.
    @Test
    fun `a refresh completing after logout does not write the token`() = runTest {
        val users = userManager(microToken = testJwt(expiresInMs = -60_000L))
        coEvery { httpService.fetchAuthToken(any()) } coAnswers {
            fetchCount.incrementAndGet()
            users.setUserData(UserData())
            BaseResponse(ver = 1, status = 0, reason = null, data = AuthToken(freshToken))
        }

        assertEquals("", manager(users).current())
        assertEquals(1, fetchCount.get())
        assertNull(users.getUserData()?.microToken)
    }

    private companion object {
        const val CONCURRENT = 10

        /** Whole second, so a token's `exp` survives the second-resolution truncation intact. */
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
