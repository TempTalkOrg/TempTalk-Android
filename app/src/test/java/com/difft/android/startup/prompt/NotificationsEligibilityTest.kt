package com.difft.android.startup.prompt

import android.app.NotificationManager
import com.difft.android.base.notification.ChannelReading
import com.difft.android.base.notification.NotificationReadinessEvaluator
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.ReadinessInput
import com.difft.android.base.notification.ReadinessIssue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notifications slot reads readiness N1/N2 instead of channel code and must be eligible in
 * exactly the cases `MessageNotificationUtil.canShowNotifications()` returns false: permission
 * denied, app switch off, MESSAGE group blocked, MESSAGE category missing or NONE.
 */
class NotificationsEligibilityTest {

    private val high = ChannelReading(importance = NotificationManager.IMPORTANCE_HIGH, hasSound = true)

    private fun input(
        sdk: Int = 34,
        granted: Boolean = true,
        enabled: Boolean = true,
        groupBlocked: Boolean = false,
        message: ChannelReading? = high,
        fullScreen: Boolean? = true,
    ) = ReadinessInput(
        sdkInt = sdk,
        postNotificationsGranted = granted,
        appNotificationsEnabled = enabled,
        messageGroupBlocked = groupBlocked,
        messageChannel = message,
        callChannel = high,
        criticalChannel = high,
        canUseFullScreenIntent = fullScreen,
        oemBrand = OemBrand.OTHER,
    )

    private fun answer(input: ReadinessInput, requestable: Boolean = false) =
        notificationsAnswer(NotificationReadinessEvaluator.evaluate(input), requestable)

    @Test
    fun `permission or app switch off is eligible with the app-notifications fix`() {
        val denied = answer(input(granted = false), requestable = true)
        assertEquals(ProviderAnswer(Eligibility.ELIGIBLE, ReadinessIssue.APP_NOTIFICATIONS_OFF, permissionRequestable = true), denied)
        assertEquals(Eligibility.ELIGIBLE, answer(input(enabled = false)).eligibility)
        assertEquals(ReadinessIssue.APP_NOTIFICATIONS_OFF, answer(input(enabled = false)).issue)
    }

    @Test
    fun `message category off, missing or blocked is eligible with the category fix`() {
        val none = ChannelReading(importance = NotificationManager.IMPORTANCE_NONE, hasSound = false)
        listOf(input(message = none), input(message = null), input(groupBlocked = true)).forEach {
            val a = answer(it)
            assertEquals(Eligibility.ELIGIBLE, a.eligibility)
            assertEquals(ReadinessIssue.MESSAGE_CATEGORY_OFF, a.issue)
        }
    }

    @Test
    fun `unspecified importance is unknown`() {
        val unspecified = ChannelReading(importance = NotificationManager.IMPORTANCE_UNSPECIFIED, hasSound = true)
        assertEquals(Eligibility.UNKNOWN, answer(input(message = unspecified)).eligibility)
    }

    @Test
    fun `categories that still show notifications are not a prompt`() {
        val low = ChannelReading(importance = NotificationManager.IMPORTANCE_LOW, hasSound = false)
        val silent = ChannelReading(importance = NotificationManager.IMPORTANCE_HIGH, hasSound = false)
        listOf(input(), input(message = low), input(message = silent), input(sdk = 25, message = null)).forEach {
            assertEquals(Eligibility.NOT_ELIGIBLE, answer(it).eligibility)
        }
    }

    @Test
    fun `full-screen slot keeps the API 34 denied-permission trigger`() {
        val denied = fullScreenAnswer(NotificationReadinessEvaluator.evaluate(input(fullScreen = false)))
        assertEquals(ProviderAnswer(Eligibility.ELIGIBLE, ReadinessIssue.FULL_SCREEN_DENIED), denied)
        assertEquals(Eligibility.NOT_ELIGIBLE, fullScreenAnswer(NotificationReadinessEvaluator.evaluate(input(fullScreen = true))).eligibility)
        assertEquals(Eligibility.NOT_ELIGIBLE, fullScreenAnswer(NotificationReadinessEvaluator.evaluate(input(sdk = 33, fullScreen = null))).eligibility)
    }
}
