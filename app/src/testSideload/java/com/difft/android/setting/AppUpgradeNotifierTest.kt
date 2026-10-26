package com.difft.android.setting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationChannelIds
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric tests for [AppUpgradeNotifier]. Asserts channels (#1354) and per-state notification
 * flags / PendingIntent presence via [org.robolectric.shadows.ShadowNotificationManager].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpgradeNotifierTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var notifier: AppUpgradeNotifier

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notifier = AppUpgradeNotifier(context)
    }

    private fun postedNotification(): Notification? =
        shadowOf(notificationManager).getNotification(AppUpgradeNotifier.NOTIFICATION_ID)

    private fun terminalNotification(): Notification? =
        shadowOf(notificationManager).getNotification(AppUpgradeNotifier.NOTIFICATION_ID_TERMINAL)

    @Test
    fun showProgress_postsOngoingAlertOnceNotification() {
        notifier.showProgress(50)

        val notification = postedNotification()
        assertNotNull(notification)
        assertTrue(
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            "progress notification must be ongoing"
        )
        assertTrue(
            notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            "progress notification must be silent / alert-once"
        )
    }

    @Test
    fun progress_sitsOnRunningInBackground_terminalOnOtherAlerts() {
        notifier.showProgress(0)
        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, postedNotification()?.channelId)

        notifier.showFailed("https://example.com/app.apk", "deadbeef", File(context.filesDir, "r.apk").absolutePath, false)
        assertEquals(NotificationChannelIds.OTHER_ALERTS, terminalNotification()?.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, notificationManager.getNotificationChannel(NotificationChannelIds.OTHER_ALERTS).importance)
        assertNull(notificationManager.getNotificationChannel("app_upgrade"))
    }

    @Test
    fun buildProgress_staysOnTheVerifiedChannel() {
        notifier.buildProgress(null, NotificationChannelIds.MESSAGE)

        notifier.showProgress(10)

        val notification = postedNotification()
        assertEquals(NotificationChannelIds.MESSAGE, notification?.channelId)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification?.groupAlertBehavior)
    }

    // Uses showFailed: FileProvider caches its roots across tests, so only one test here may
    // go through getUriForFile.
    @Test
    fun terminal_recreatesAMissingOtherAlertsChannel() {
        notificationManager.deleteNotificationChannel(NotificationChannelIds.OTHER_ALERTS)
        assertNull(notificationManager.getNotificationChannel(NotificationChannelIds.OTHER_ALERTS))

        notifier.showFailed("https://example.com/app.apk", "deadbeef", File(context.filesDir, "r.apk").absolutePath, false)

        assertNotNull(notificationManager.getNotificationChannel(NotificationChannelIds.OTHER_ALERTS))
        assertEquals(NotificationChannelIds.OTHER_ALERTS, terminalNotification()?.channelId)
    }

    @Test
    fun terminalNotifications_areDroppedWhenTheChannelCannotBeCreated() {
        val capped = mockk<NotificationManager>(relaxed = true) {
            every { getNotificationChannel(any()) } returns null
            every { createNotificationChannel(any<NotificationChannel>()) } throws IllegalStateException("Limit exceed")
            every { areNotificationsEnabled() } returns true
        }
        Shadow.extract<ShadowContextImpl>((context as android.app.Application).baseContext)
            .setSystemService(Context.NOTIFICATION_SERVICE, capped)
        val cappedNotifier = AppUpgradeNotifier(context)

        cappedNotifier.showFailed("https://example.com/app.apk", "deadbeef", File(context.filesDir, "r.apk").absolutePath, false)
        cappedNotifier.showProgress(5)

        verify(exactly = 0) { capped.notify(any(), any(), any()) }
        verify(exactly = 0) { capped.notify(any<Int>(), any()) }
    }

    @Test
    fun showComplete_postsToTerminalIdAndCancelsProgress() {
        // File must live under a FileProvider-configured root so getUriForFile resolves.
        val apkFile = File(context.filesDir, "upgrade_test.apk").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        // Progress notification present; terminal post must cancel it and post under its own id.
        notifier.showProgress(80)

        notifier.showComplete(apkFile)

        assertNull(postedNotification(), "progress notification must be cancelled on complete")
        val notification = terminalNotification()
        assertNotNull(notification, "complete notification must post under terminal id")
        assertNotNull(notification.contentIntent, "complete notification must carry install PendingIntent")
        assertTrue(
            notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
            "complete notification must be auto-cancel"
        )
    }

    @Test
    fun showFailed_postsToTerminalIdAndCancelsProgress() {
        notifier.showVerifying()

        notifier.showFailed(
            url = "https://example.com/app.apk",
            apkHash = "deadbeef",
            filePath = File(context.filesDir, "retry.apk").absolutePath,
            isForce = false
        )

        assertNull(postedNotification(), "progress notification must be cancelled on failure")
        val notification = terminalNotification()
        assertNotNull(notification, "failed notification must post under terminal id")
        assertNotNull(notification.contentIntent, "failed notification must carry retry PendingIntent")

        // The retry goes through the receiver, whose starter runs the channel preflight,
        // never straight to the foreground service.
        val pending = shadowOf(notification.contentIntent)
        assertTrue(pending.isBroadcast, "retry must be a broadcast, not a foreground-service start")
        val retry = pending.savedIntent
        val expected = AppUpgradeService.startIntent(
            context, "https://example.com/app.apk", "deadbeef", File(context.filesDir, "retry.apk").absolutePath, false
        )
        assertEquals(AppUpgradeRetryReceiver::class.java.name, retry.component?.className)
        listOf(AppUpgradeService.EXTRA_URL, AppUpgradeService.EXTRA_HASH, AppUpgradeService.EXTRA_PATH).forEach {
            assertEquals(expected.getStringExtra(it), retry.getStringExtra(it), it)
        }
        assertEquals(false, retry.getBooleanExtra(AppUpgradeService.EXTRA_FORCE, true))
    }

    @Test
    fun cancelTerminal_clearsTerminalNotification() {
        notifier.showFailed(
            url = "https://example.com/app.apk",
            apkHash = "deadbeef",
            filePath = File(context.filesDir, "retry.apk").absolutePath,
            isForce = false
        )
        assertNotNull(terminalNotification(), "failed notification must be posted first")

        notifier.cancelTerminal()

        assertNull(terminalNotification(), "cancelTerminal must clear the terminal notification")
    }
}
