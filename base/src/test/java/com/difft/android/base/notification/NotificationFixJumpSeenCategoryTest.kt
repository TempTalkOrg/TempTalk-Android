package com.difft.android.base.notification

import android.content.Intent
import android.provider.Settings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull


@RunWith(RobolectricTestRunner::class)
class NotificationFixJumpSeenCategoryTest {

    private fun channelPage(id: String) = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, id)

    private val appPage = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)

    @Test
    fun `an opened category page records its channel`() {
        assertEquals(
            NotificationChannelIds.MESSAGE,
            NotificationFixJump.seenCategory(NotificationFix.MESSAGE_CATEGORY, channelPage(NotificationChannelIds.MESSAGE)),
        )
        assertEquals(
            NotificationChannelIds.CALL,
            NotificationFixJump.seenCategory(NotificationFix.CALL_CATEGORY, channelPage(NotificationChannelIds.CALL)),
        )
        assertEquals(
            NotificationChannelIds.CRITICAL_ALERT,
            NotificationFixJump.seenCategory(NotificationFix.CRITICAL_ALERT_CATEGORY, channelPage(NotificationChannelIds.CRITICAL_ALERT)),
        )
    }

    @Test
    fun `a failed jump records nothing`() {
        NotificationFix.entries.forEach { fix ->
            assertNull(NotificationFixJump.seenCategory(fix, opened = null), "fix=$fix")
        }
    }

    @Test
    fun `an app-level fallback page records nothing`() {
        listOf(NotificationFix.MESSAGE_CATEGORY, NotificationFix.CALL_CATEGORY, NotificationFix.CRITICAL_ALERT_CATEGORY).forEach { fix ->
            assertNull(NotificationFixJump.seenCategory(fix, appPage), "fix=$fix")
        }
    }

    @Test
    fun `pages other than a category page record nothing`() {
        val categories = setOf(NotificationFix.MESSAGE_CATEGORY, NotificationFix.CALL_CATEGORY, NotificationFix.CRITICAL_ALERT_CATEGORY)
        NotificationFix.entries.filter { it !in categories }.forEach { fix ->
            assertNull(NotificationFixJump.seenCategory(fix, channelPage(NotificationChannelIds.MESSAGE)), "fix=$fix")
        }
    }
}
