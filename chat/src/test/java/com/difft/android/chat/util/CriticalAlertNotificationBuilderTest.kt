package com.difft.android.chat.util

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.LCallConstants
import com.difft.android.chat.common.StopCriticalAlertSoundReceiver
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pins the notification produced by [CriticalAlertNotificationBuilder]: FULL_SCREEN must stay
 * identical to the pre-extraction inline builder, and SILENT_COMPANION must carry the androidx
 * `setSilent` output (silent group + GROUP_ALERT_SUMMARY) that the system's mute and heads-up
 * suppression key on.
 */
@RunWith(RobolectricTestRunner::class)
class CriticalAlertNotificationBuilderTest {

    private val channelId = "CRITICAL_ALERT_V2"
    private val payload = CriticalAlertPayload(
        conversationId = "+10001",
        title = "Alert",
        content = "Server down",
        notificationId = 42,
        timestamp = 1_700_000_000_123L,
        roomId = "room-1",
    )

    private lateinit var context: Application
    private lateinit var builder: CriticalAlertNotificationBuilder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val activityProvider = mockk<ActivityProvider>()
        every { activityProvider.getActivityClass(ActivityType.CRITICAL_ALERT) } returns Activity::class.java
        builder = CriticalAlertNotificationBuilder(context, activityProvider)
    }

    // N10
    @Suppress("DEPRECATION")
    @Test
    fun `silent companion has no alerting, no full screen or delete intent, and times out`() {
        val n = builder.build(payload, channelId, CriticalAlertPresentation.SILENT_COMPANION)

        assertNull(n.fullScreenIntent)
        assertNull(n.deleteIntent)
        assertEquals(CriticalAlertNotificationBuilder.CRITICAL_ALERT_COMPANION_TIMEOUT_MS, n.timeoutAfter)
        assertEquals(60_000L, n.timeoutAfter)
        assertEquals(NotificationCompat.GROUP_KEY_SILENT, n.group)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        assertNull(n.sound)
        assertNull(n.vibrate)
        assertEquals(channelId, n.channelId)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT == 0)

        // Tap re-surfaces the alert page: no CLICKED action, so no auto-join.
        val click = shadowOf(n.contentIntent).savedIntent
        assertTrue(shadowOf(n.contentIntent).isActivityIntent)
        assertEquals(payload.notificationId, shadowOf(n.contentIntent).requestCode)
        assertNull(click.action)
        assertActivityExtras(click)
    }

    // N11
    @Suppress("DEPRECATION")
    @Test
    fun `full screen matches the pre-extraction notification`() {
        val n = builder.build(payload, channelId, CriticalAlertPresentation.FULL_SCREEN)

        assertNotNull(n.fullScreenIntent)
        assertNotNull(n.deleteIntent)
        assertEquals(0L, n.timeoutAfter)
        assertNull(n.group)
        assertEquals(channelId, n.channelId)
        assertEquals("🚨Alert", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("🚨Alert", n.extras.getCharSequence(Notification.EXTRA_TITLE_BIG).toString())
        assertEquals("Server down", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Server down", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(payload.timestamp, n.`when`)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
        assertEquals(Notification.PRIORITY_MAX, n.priority)
        assertEquals(Notification.CATEGORY_ALARM, n.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(Notification.BADGE_ICON_NONE, n.badgeIconType)
    }

    // N12
    @Test
    fun `full screen intents carry the critical alert extras`() {
        val n = builder.build(payload, channelId, CriticalAlertPresentation.FULL_SCREEN)

        val click = shadowOf(n.contentIntent).savedIntent
        assertTrue(shadowOf(n.contentIntent).isActivityIntent)
        assertEquals(payload.notificationId, shadowOf(n.contentIntent).requestCode)
        assertEquals(LCallConstants.CRITICAL_ALERT_ACTION_CLICKED, click.action)
        assertActivityExtras(click)

        val fullScreen = shadowOf(n.fullScreenIntent).savedIntent
        assertTrue(shadowOf(n.fullScreenIntent).isActivityIntent)
        assertEquals(payload.notificationId, shadowOf(n.fullScreenIntent).requestCode)
        assertNull(fullScreen.action)
        assertActivityExtras(fullScreen)

        val delete = shadowOf(n.deleteIntent)
        assertTrue(delete.isBroadcast)
        assertEquals(payload.notificationId, delete.requestCode)
        assertEquals(StopCriticalAlertSoundReceiver::class.java.name, delete.savedIntent.component?.className)
        assertEquals(MessageNotificationUtil.STOP_CRITICAL_ALERT_SOUND, delete.savedIntent.action)
        assertEquals(context.packageName, delete.savedIntent.`package`)
        assertEquals(payload.notificationId, delete.savedIntent.getIntExtra("notification_id", -1))
    }

    private fun assertActivityExtras(intent: Intent) {
        assertEquals(Activity::class.java.name, intent.component?.className)
        assertEquals(payload.conversationId, intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_CONVERSATION))
        assertEquals(payload.title, intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_TITLE))
        assertEquals(payload.content, intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_MESSAGE))
        assertEquals(payload.notificationId, intent.getIntExtra(LCallConstants.BUNDLE_KEY_CRITICAL_NOTIFICATION_ID, -1))
        assertTrue(intent.getBooleanExtra(LCallConstants.BUNDLE_KEY_CRITICAL_IS_NOTIFICATION, false))
        assertEquals(payload.roomId, intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_ROOM_ID))
    }
}
