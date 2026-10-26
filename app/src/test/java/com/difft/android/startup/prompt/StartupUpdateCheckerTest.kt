package com.difft.android.startup.prompt

import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.PackageUtil
import com.difft.android.network.BaseResponse
import com.difft.android.setting.data.CheckUpdateResponse
import com.difft.android.setting.repo.SettingRepo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The forced-update gate expires 30 minutes after a hand-off and the normal recheck restores it (#1355). */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupUpdateCheckerTest {

    private val minute = 60_000L
    private val forced = CheckUpdateResponse(update = true, force = true, url = "u", notes = "n", updateWithApk = true, apkHash = "h")
    private val settingRepo: SettingRepo = mockk()
    private val environmentHelper: EnvironmentHelper = mockk { every { isInsiderChannel() } returns false }

    @Before
    fun setUp() {
        mockkObject(PackageUtil)
        every { PackageUtil.getAppVersionName() } returns "1.0.0"
        coEvery { settingRepo.checkUpdate(any(), any()) } returns BaseResponse(ver = 1, status = 0, reason = null, data = forced)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun TestScope.checker() =
        StartupUpdateChecker(settingRepo, environmentHelper, mockk<UserManager>(relaxed = true), backgroundScope) { testScheduler.currentTime }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `forced offer gates until thirty minutes after a hand-off, then the recheck restores it`() = runTest {
        val checker = checker()
        checker.onSessionStart()
        advance(StartupUpdateChecker.START_DELAY_MS + 1)
        assertEquals(forced, checker.blockingForced.value)

        checker.onForcedHandOff()
        advance(29 * minute)
        assertEquals(forced, checker.blockingForced.value)
        advance(minute + 1)
        assertNull(checker.blockingForced.value)
        assertEquals(UpdateCheckState.Forced(forced), checker.state.value)

        checker.onSessionStart()
        advance(StartupUpdateChecker.START_DELAY_MS + 1)
        assertEquals(forced, checker.blockingForced.value)
    }

    @Test
    fun `a recheck returning the handed-off offer keeps the expiry running`() = runTest {
        val checker = checker()
        checker.onSessionStart()
        advance(StartupUpdateChecker.START_DELAY_MS + 1)
        advance(31 * minute)
        checker.onForcedHandOff()
        // Same forced offer again, 30+ min after the first check and 2 s into the hand-off window.
        checker.onSessionStart()
        advance(StartupUpdateChecker.START_DELAY_MS + 1)
        assertEquals(forced, checker.blockingForced.value)
        advance(29 * minute)
        assertEquals(forced, checker.blockingForced.value)
        advance(minute)
        assertNull("the hand-off still expires on time", checker.blockingForced.value)
    }

    @Test
    fun `a different forced offer during the hand-off window cancels the expiry and gates`() = runTest {
        val checker = checker()
        checker.onSessionStart()
        advance(StartupUpdateChecker.START_DELAY_MS + 1)
        advance(31 * minute)
        checker.onForcedHandOff()
        val newer = forced.copy(url = "u2", apkHash = "h2")
        coEvery { settingRepo.checkUpdate(any(), any()) } returns BaseResponse(ver = 1, status = 0, reason = null, data = newer)
        checker.onSessionStart()
        advance(StartupUpdateChecker.START_DELAY_MS + 1)
        advance(40 * minute)
        assertEquals("a new offer is not expired by the old hand-off", newer, checker.blockingForced.value)
    }

    @Test
    fun `hand-off without a forced offer does nothing`() = runTest {
        val checker = checker()
        checker.onForcedHandOff()
        advance(60 * minute)
        assertNull(checker.blockingForced.value)
    }
}
