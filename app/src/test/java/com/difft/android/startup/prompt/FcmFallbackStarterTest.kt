package com.difft.android.startup.prompt

import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.chat.messages.MessageServiceManager
import com.difft.android.push.FcmInitResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The silent fallback start keeps the old IndexActivity.handleFcmUnavailable conditions, and the
 * BACKGROUND_CONNECTION prompt is eligible exactly where that start cannot happen.
 */
class FcmFallbackStarterTest {

    private val userManager: UserManager = mockk(relaxed = true)
    private val serviceManager: MessageServiceManager = mockk(relaxed = true)

    private fun starter(running: Boolean, autoStart: Boolean?, requirementsMet: Boolean): FcmFallbackStarter {
        every { userManager.getUserData() } returns autoStart?.let { UserData(autoStartMessageService = it) }
        every { serviceManager.checkBackgroundConnectionRequirements() } returns requirementsMet
        return FcmFallbackStarter(userManager, serviceManager) { running }
    }

    @Test
    fun `starts when auto-start is on and requirements are met`() {
        starter(running = false, autoStart = true, requirementsMet = true).onFcmUnavailable()
        verify(exactly = 1) { serviceManager.startService() }
    }

    @Test
    fun `missing user data defaults to auto-start`() {
        starter(running = false, autoStart = null, requirementsMet = true).onFcmUnavailable()
        verify(exactly = 1) { serviceManager.startService() }
    }

    @Test
    fun `never starts when running, requirements unmet or auto-start off`() {
        starter(running = true, autoStart = true, requirementsMet = true).onFcmUnavailable()
        starter(running = false, autoStart = true, requirementsMet = false).onFcmUnavailable()
        starter(running = false, autoStart = false, requirementsMet = true).onFcmUnavailable()
        verify(exactly = 0) { serviceManager.startService() }
    }

    @Test
    fun `auto-start off does not even read the requirements`() {
        starter(running = false, autoStart = false, requirementsMet = true).onFcmUnavailable()
        verify(exactly = 0) { serviceManager.checkBackgroundConnectionRequirements() }
    }

    @Test
    fun `prompt eligibility is the complement of the silent start`() {
        val failure = FcmInitResult.Failure("timeout")
        val noGms = FcmInitResult.PlayServicesUnavailable(1)
        fun eligibility(fcm: FcmInitResult, running: Boolean = false, autoStart: Boolean = true, met: Boolean = false) =
            backgroundConnectionEligibility(fcm, running, autoStart) { met }

        assertEquals(Eligibility.ELIGIBLE, eligibility(failure, met = false))
        assertEquals(Eligibility.ELIGIBLE, eligibility(noGms, autoStart = false, met = true))
        assertEquals(Eligibility.NOT_ELIGIBLE, eligibility(noGms, met = true))
        assertEquals(Eligibility.NOT_ELIGIBLE, eligibility(failure, running = true))
        assertEquals(Eligibility.NOT_ELIGIBLE, eligibility(FcmInitResult.Success("t")))
        assertEquals(Eligibility.UNKNOWN, eligibility(FcmInitResult.Idle))
        assertEquals(Eligibility.UNKNOWN, eligibility(FcmInitResult.Loading))
        assertEquals(Eligibility.NOT_ELIGIBLE, eligibility(FcmInitResult.Loading, running = true))
    }
}
