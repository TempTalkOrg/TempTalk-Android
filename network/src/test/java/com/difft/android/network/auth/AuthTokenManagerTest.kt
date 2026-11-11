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











@RunWith(RobolectricTestRunner::class)
class AuthTokenManagerTest {

    private val httpService = mockk<HttpService>()
    private val authClient = mockk<ChativeHttpClient>()
    private val fetchCount = AtomicInteger(0)

    
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

    
    private fun managerAt(now: Long, userManager: UserManager): AuthTokenManager {
        ServerTimeProvider.resetForTest(wallClock = { now }, elapsedClock = { 0L })
        return manager(userManager).apply { nowMs = { now } }
    }

    @Before
    fun setUp() {
        
        
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

    
    @Test
    fun `current returns a stored token that is not expiring`() = runTest {
        stubRefresh()
        val stored = testJwt(expiresInMs = 10 * 60_000L)

        val token = manager(userManager(microToken = stored)).current()

        assertEquals(stored, token)
        assertEquals(0, fetchCount.get())
    }

    
    @Test
    fun `current refreshes a token inside the expiry margin`() = runTest {
        stubRefresh()
        val users = userManager(microToken = testJwt(expiresInMs = 60_000L))

        val token = manager(users).current()

        assertEquals(freshToken, token)
        assertEquals(1, fetchCount.get())
        assertEquals(freshToken, users.getUserData()?.microToken)
    }

    
    @Test
    fun `current keeps a token just outside the expiry margin`() = runTest {
        stubRefresh()
        val stored = testJwt(expiresInMs = 121_000L)

        val token = manager(userManager(microToken = stored)).current()

        assertEquals(stored, token)
        assertEquals(0, fetchCount.get())
    }

    
    @Test
    fun `current returns empty when there is no credential`() = runTest {
        stubRefresh()
        val users = userManager(baseAuth = null, microToken = testJwt(expiresInMs = 10 * 60_000L))

        assertEquals("", manager(users).current())
        assertEquals(0, fetchCount.get())
    }

    
    @Test
    fun `current returns empty before storage warm up`() = runTest {
        stubRefresh()

        assertEquals("", manager(FakeUserManager(null)).current())
        assertEquals(0, fetchCount.get())
    }

    
    @Test
    fun `current refreshes an unparsable stored token`() = runTest {
        stubRefresh()

        val token = manager(userManager(microToken = "not-a-jwt")).current()

        assertEquals(freshToken, token)
        assertEquals(1, fetchCount.get())
    }

    
    @Test
    fun `concurrent current calls issue one refresh`() = runTest {
        stubRefresh()
        val subject = manager(userManager(microToken = testJwt(expiresInMs = -60_000L)))

        val tokens = List(CONCURRENT) { async { subject.current() } }.awaitAll()

        assertEquals(1, fetchCount.get())
        assertTrue(tokens.all { it == freshToken })
    }

    
    @Test
    fun `concurrent refreshRejected calls for the same token issue one refresh`() = runTest {
        stubRefresh()
        
        val stale = testJwt(expiresInMs = 9 * 60_000L, uid = "stale")
        val subject = manager(userManager(microToken = stale))

        val tokens = List(CONCURRENT) { async { subject.refreshRejected(stale) } }.awaitAll()

        assertEquals(1, fetchCount.get())
        assertTrue(tokens.all { it == freshToken })
    }

    
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
        
        repeat(3) { assertEquals("", subject.refreshRejected(stale)) }
        assertEquals(1, fetchCount.get())

        fakeNow += AuthTokenManager.REFRESH_FAILURE_COOLDOWN_MS + 1
        subject.refreshRejected(stale)

        assertEquals(2, fetchCount.get())
    }

    
    @Test
    fun `the refresh client parameter is qualified with AuthToken`() {
        val annotations = AuthTokenManager::class.java.constructors.single().parameterAnnotations
        val clientAnnotations = annotations.first().map { it.annotationClass }

        assertTrue(clientAnnotations.contains(ChativeHttpClientModule.AuthToken::class))
    }

    
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
        
        assertEquals(stillValid, subject.current())
        assertEquals(1, fetchCount.get())
    }

    
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

    
    @Test
    fun `current keeps a token with exactly the expiry margin left`() = runTest {
        stubRefresh()
        val stored = testJwtExpiringAt(FIXED_NOW + AuthTokenManager.TOKEN_EXPIRY_MARGIN_MS)
        val users = userManager(microToken = stored)

        assertEquals(stored, managerAt(FIXED_NOW, users).current())
        assertEquals(0, fetchCount.get())
    }

    
    
    @Test
    fun `current refreshes a token one ms inside the expiry margin`() = runTest {
        stubRefresh()
        val stored = testJwtExpiringAt(FIXED_NOW + AuthTokenManager.TOKEN_EXPIRY_MARGIN_MS - 1)
        val users = userManager(microToken = stored)

        assertEquals(freshToken, managerAt(FIXED_NOW, users).current())
        assertEquals(1, fetchCount.get())
    }

    
    @Test
    fun `refreshRejected returns the stored token when it is already newer`() = runTest {
        stubRefresh()
        val stored = testJwt(expiresInMs = 9 * 60_000L, uid = "newer")
        val users = userManager(microToken = stored)

        assertEquals(stored, manager(users).refreshRejected("stale"))
        assertEquals(0, fetchCount.get())
    }

    
    @Test
    fun `refreshRejected returns empty when there is no credential`() = runTest {
        stubRefresh()
        val users = userManager(baseAuth = "", microToken = testJwt(expiresInMs = -60_000L))

        assertEquals("", manager(users).refreshRejected("stale"))
        assertEquals(0, fetchCount.get())
    }

    
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

        
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
