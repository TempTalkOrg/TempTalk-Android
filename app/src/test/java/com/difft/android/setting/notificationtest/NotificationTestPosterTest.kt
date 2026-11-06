package com.difft.android.setting.notificationtest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOwner
import com.difft.android.base.notification.NotificationTestRuns
import com.difft.android.test.TestDispatcherRule
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationTestPosterTest {
    @get:Rule val dispatcherRule = TestDispatcherRule(StandardTestDispatcher())
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `post requires its lease and an old cancellation removes only its tag`() = runTest {
        val manager = context.getSystemService(NotificationManager::class.java)!!
        manager.createNotificationChannel(NotificationChannel(NotificationChannelIds.MESSAGE, "Message", NotificationManager.IMPORTANCE_HIGH))
        val runs = NotificationTestRuns()
        val poster = NotificationTestPoster(context, runs)
        val owner = NotificationTestOwner(UUID.randomUUID().toString(), NotificationTestOwner.Host.HOSTED)
        val first = runs.claim(owner, NotificationTestKind.BANNER)!!
        assertTrue(poster.post(first))
        val firstNotification = manager.activeNotifications.single()
        assertEquals(NotificationTestPoster.tagOf(first.runId), firstNotification.tag)
        assertEquals(NotificationTestReturnRoute.of(first), NotificationTestReturnRoute.read(shadowOf(firstNotification.notification.contentIntent).savedIntent))
        assertTrue(firstNotification.notification.contentIntent.isImmutable)
        runs.release(first)
        val next = runs.claim(owner, NotificationTestKind.BANNER)!!
        assertTrue(poster.post(next))
        assertFalse(poster.post(first))
        val nextNotification = manager.activeNotifications.first { it.tag == NotificationTestPoster.tagOf(next.runId) }
        assertNotEquals(firstNotification.notification.contentIntent, nextNotification.notification.contentIntent)
        poster.cancel(first.runId, first.kind)
        assertEquals(listOf(NotificationTestPoster.tagOf(next.runId)), manager.activeNotifications.map { it.tag })
        poster.cancel(next.runId, next.kind)
    }
}
