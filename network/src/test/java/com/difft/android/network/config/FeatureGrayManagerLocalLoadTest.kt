package com.difft.android.network.config

import com.difft.android.base.user.UserData
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.TestScopeApplication
import com.difft.android.network.responses.GrayConfigData
import com.difft.android.test.fakes.FakeUserManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue







@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class FeatureGrayManagerLocalLoadTest {

    private val userManager = FakeUserManager(UserData(account = "acc", microToken = "jwt", grayMapJson = """{"quic":true}"""))
    private var serverGray: List<GrayConfigData> = emptyList()
    private val service = mockk<HttpService> {
        coEvery { grayCheck(any()) } answers { BaseResponse(ver = 1, status = 0, reason = null, data = serverGray) }
    }
    private val defaultUserManagerProvider = FeatureGrayManager.userManagerProvider
    private val defaultClientProvider = FeatureGrayManager.chatHttpClientProvider

    @Before
    fun setUp() {
        FeatureGrayManager.resetForTest()
        FeatureGrayManager.userManagerProvider = { userManager }
        FeatureGrayManager.chatHttpClientProvider = { mockk<ChativeHttpClient> { every { httpService } returns service } }
    }

    @After
    fun tearDown() {
        FeatureGrayManager.resetForTest()
        FeatureGrayManager.userManagerProvider = defaultUserManagerProvider
        FeatureGrayManager.chatHttpClientProvider = defaultClientProvider
    }

    @Test
    fun `the first read before init sees the persisted value`() = runTest {
        assertTrue(FeatureGrayManager.isEnabled(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK))
    }

    @Test
    fun `a server value already cached is not overwritten by the persisted map`() = runTest {
        serverGray = listOf(GrayConfigData(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK, isInGray = false))
        FeatureGrayManager.refreshFromServer(listOf(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK))
        userManager.setUserData(userManager.getUserData()!!.copy(grayMapJson = """{"quic":true}"""), commit = false)

        assertFalse(FeatureGrayManager.isEnabled(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK))
    }

    @Test
    fun `init and a concurrent first read load the persisted map once and keep the server value`() = runTest {
        serverGray = listOf(GrayConfigData(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK, isInGray = false))
        val read = async { FeatureGrayManager.isEnabled(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK) }
        FeatureGrayManager.init()
        read.await()
        
        userManager.setUserData(userManager.getUserData()!!.copy(grayMapJson = """{"quic":true}"""), commit = false)

        assertFalse(FeatureGrayManager.isEnabled(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK))
    }

    @Test
    fun `a corrupt persisted map reads as off without throwing`() = runTest {
        userManager.setUserData(userManager.getUserData()!!.copy(grayMapJson = "{not json"), commit = false)

        assertFalse(FeatureGrayManager.isEnabled(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK))
    }

    @Test
    fun `a read before login does not block the load after login`() = runTest {
        val stored = userManager.getUserData()!!
        userManager.clearUserData()
        assertFalse(FeatureGrayManager.isEnabled(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK))

        userManager.setUserData(stored.copy(microToken = null), commit = false)
        FeatureGrayManager.init()

        assertTrue(FeatureGrayManager.isEnabled(FeatureGrayManager.FEATURE_GRAY_CALL_QUICK))
    }
}
