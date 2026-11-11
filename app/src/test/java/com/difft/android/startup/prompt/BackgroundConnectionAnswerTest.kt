package com.difft.android.startup.prompt

import com.difft.android.push.FcmInitResult
import org.junit.Assert.assertEquals
import org.junit.Test


class BackgroundConnectionAnswerTest {

    private val fcmOk = FcmInitResult.Success("token")
    private val fcmFailed = FcmInitResult.Failure("timeout")

    private fun answer(
        fcm: FcmInitResult = fcmOk,
        running: Boolean = false,
        autoStart: Boolean = true,
        requirementsMet: Boolean = false,
        notifications: Boolean = true,
        problemDays: Int = 0,
    ) = backgroundConnectionAnswer(fcm, running, autoStart, { requirementsMet }, notifications, problemDays)

    @Test
    fun `nothing holds for a healthy device`() {
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, answer())
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, answer(problemDays = 1))
    }

    @Test
    fun `a running connection or disabled notifications never prompt`() {
        val all = answer(fcm = fcmFailed, problemDays = 2)
        assertEquals(Eligibility.ELIGIBLE, all.eligibility)
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, answer(fcm = fcmFailed, problemDays = 2, running = true))
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, answer(fcm = fcmFailed, problemDays = 2, notifications = false))
    }

    @Test
    fun `trigger 1 is the unchanged fcm rule`() {
        assertEquals(setOf(BackgroundTrigger.FCM_UNAVAILABLE), answer(fcm = fcmFailed).triggers)
        assertEquals(setOf(BackgroundTrigger.FCM_UNAVAILABLE), answer(fcm = FcmInitResult.PlayServicesUnavailable(1), autoStart = false, requirementsMet = true).triggers)
        
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, answer(fcm = fcmFailed, requirementsMet = true))
    }

    @Test
    fun `trigger 2 needs late deliveries on two days of the window`() {
        assertEquals(ProviderAnswer.NOT_ELIGIBLE, answer(problemDays = 1))
        assertEquals(setOf(BackgroundTrigger.LATE_DELIVERY), answer(problemDays = 2).triggers)
        assertEquals(Eligibility.ELIGIBLE, answer(fcm = fcmOk, problemDays = 7).eligibility)
    }




    @Test
    fun `every holding trigger is reported`() {
        val a = answer(fcm = fcmFailed, problemDays = 2)
        assertEquals(BackgroundTrigger.entries.toSet(), a.triggers)
    }

    @Test
    fun `an undecided fcm result is unknown unless another trigger already holds`() {
        assertEquals(ProviderAnswer.UNKNOWN, answer(fcm = FcmInitResult.Loading))
        assertEquals(ProviderAnswer.UNKNOWN, answer(fcm = FcmInitResult.Idle))
        assertEquals(setOf(BackgroundTrigger.LATE_DELIVERY), answer(fcm = FcmInitResult.Loading, problemDays = 2).triggers)
    }
}
