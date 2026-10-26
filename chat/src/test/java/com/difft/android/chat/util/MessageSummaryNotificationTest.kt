package com.difft.android.chat.util

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationChannelIds
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #1354: the summary moves to MESSAGE and stays silent through GROUP_ALERT_CHILDREN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageSummaryNotificationTest {

    @Test
    fun `the summary sits on MESSAGE and only its children alert`() {
        val summary = buildMessageSummaryNotification(ApplicationProvider.getApplicationContext<Context>(), "KEY")

        assertEquals(NotificationChannelIds.MESSAGE, summary.channelId)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertTrue(summary.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("KEY", summary.group)
    }
}
