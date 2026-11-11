package com.difft.android.network.config

import com.difft.android.base.user.UserData
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.TestScopeApplication
import com.difft.android.test.fakes.FakeUserManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class FeatureGrayManagerThrottleTest {

    
    private val userManager = FakeUserManager(UserData(account = "acc", microToken = "jwt", lastUseTime = 0L))
    private var status = 0
    private val service = mockk<HttpService> {
        coEvery { grayCheck(any()) } answers { BaseResponse(ver = 1, status = status, reason = null, data = emptyList()) }
    }
    private var now = 1_000_000L
    private val defaultUserManagerProvider = FeatureGrayManager.userManagerProvider
    private val defaultClientProvider = FeatureGrayManager.chatHttpClientProvider
    private val defaultClock = FeatureGrayManager.elapsedRealtime

    @Before
    fun setUp() {
        FeatureGrayManager.resetForTest()
        FeatureGrayManager.userManagerProvider = { userManager }
        FeatureGrayManager.chatHttpClientProvider = { mockk<ChativeHttpClient> { every { httpService } returns service } }
        FeatureGrayManager.elapsedRealtime = { now }
    }

    @After
    fun tearDown() {
        FeatureGrayManager.resetForTest()
        FeatureGrayManager.userManagerProvider = defaultUserManagerProvider
        FeatureGrayManager.chatHttpClientProvider = defaultClientProvider
        FeatureGrayManager.elapsedRealtime = defaultClock
    }

    @Test
    fun `foregrounds within five minutes of a successful check do not refetch`() = runTest {
        FeatureGrayManager.checkUpdateConfigFromServer()
        now += 60_000L
        FeatureGrayManager.checkUpdateConfigFromServer()
        now += 60_000L
        FeatureGrayManager.checkUpdateConfigFromServer()

        coVerify(exactly = 1) { service.grayCheck(any()) }
    }

    @Test
    fun `a check after the interval refetches`() = runTest {
        FeatureGrayManager.checkUpdateConfigFromServer()
        now += 5 * 60_000L + 1

        FeatureGrayManager.checkUpdateConfigFromServer()

        coVerify(exactly = 2) { service.grayCheck(any()) }
    }

    @Test
    fun `a failed check is not stamped and the next foreground retries`() = runTest {
        status = 500
        FeatureGrayManager.checkUpdateConfigFromServer()
        status = 0
        now += 60_000L

        FeatureGrayManager.checkUpdateConfigFromServer()

        coVerify(exactly = 2) { service.grayCheck(any()) }
    }
}
