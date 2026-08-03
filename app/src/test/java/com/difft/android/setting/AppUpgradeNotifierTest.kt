package com.difft.android.setting

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric tests for [AppUpgradeNotifier]. Asserts channel params and per-state notification
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
    fun ensureChannel_createdWithImportanceDefault() {
        notifier.showProgress(0)

        val channel = notificationManager.getNotificationChannel("app_upgrade")
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
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
