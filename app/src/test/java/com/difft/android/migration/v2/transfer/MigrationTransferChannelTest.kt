package com.difft.android.migration.v2.transfer

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.utils.ForegroundServiceStarter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #1354, transfer: the foreground notification sits on a verified channel, the
 * result on OTHER_ALERTS, and no start or promotion happens without a channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationTransferChannelTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val nm get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val posted = slot<Notification>()

    @Before
    fun setUp() {
        MigrationTransferStateHolder.reset()
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        mockkStatic(ForegroundServiceStarter::class)
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), capture(posted), any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
        MigrationTransferStateHolder.reset()
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
    }

    private fun cappedNm(vararg existing: String): NotificationManager = mockk(relaxed = true) {
        every { getNotificationChannel(any()) } answers {
            firstArg<String>().takeIf { it in existing }?.let { NotificationChannel(it, it, NotificationManager.IMPORTANCE_HIGH) }
        }
        every { createNotificationChannel(any<NotificationChannel>()) } throws IllegalStateException("Limit exceed")
    }

    private fun installNm(manager: NotificationManager) {
        Shadow.extract<ShadowContextImpl>(app.baseContext).setSystemService(Context.NOTIFICATION_SERVICE, manager)
    }

    /** The service without Hilt: constructed reflectively with only the notification helper set. */
    private fun service(): MigrationTransferService {
        val service = MigrationTransferService::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(service, app)
        MigrationTransferService::class.java.getDeclaredField("notificationHelper")
            .apply { isAccessible = true }
            .set(service, MigrationTransferNotificationHelper(app))
        return service
    }

    private fun MigrationTransferService.promote(): Boolean =
        MigrationTransferService::class.java.getDeclaredMethod("startForegroundCompat")
            .apply { isAccessible = true }
            .invoke(this) as Boolean

    @Test
    fun `the foreground notification sits on a re-created running-in-background channel`() {
        assertTrue(service().promote())

        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, posted.captured.channelId)
        assertTrue(nm.getNotificationChannel(NotificationChannelIds.RUNNING_IN_BACKGROUND) != null)
    }

    @Test
    fun `at the channel cap the foreground notification falls back to MESSAGE, silent`() {
        installNm(cappedNm(NotificationChannelIds.MESSAGE))

        assertTrue(service().promote())

        assertEquals(NotificationChannelIds.MESSAGE, posted.captured.channelId)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, posted.captured.groupAlertBehavior)
        assertTrue(posted.captured.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun `with no channel the service stops without promoting`() {
        installNm(cappedNm())
        val service = service()

        assertFalse(service.promote())

        verify(exactly = 0) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) }
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `with no channel the starters fail the transfer instead of starting the service`() {
        installNm(cappedNm())

        MigrationTransferService.startReceiver(app, account = "acc")

        assertNull(shadowOf(app).nextStartedService)
        assertEquals(
            TransferState.Failed(MigrationTransferService.REASON_NO_NOTIFICATION_CHANNEL),
            MigrationTransferStateHolder.state.value,
        )

        MigrationTransferStateHolder.reset()
        MigrationTransferService.startSender(app, rawQrPayload = "qr")
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `a starter with a channel records it and starts the service`() {
        MigrationTransferService.startReceiver(app, account = "acc")

        assertEquals(MigrationTransferService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, NotificationChannelSpecs.verifiedForegroundChannel)
    }

    @Test
    fun `the result notification re-creates OTHER_ALERTS and progress follows the foreground channel`() {
        val helper = MigrationTransferNotificationHelper(app)
        helper.updateProgress(10)
        assertNull(shadowOf(nm).getNotification(MigrationTransferNotificationHelper.NOTIFICATION_ID_RUNNING))

        helper.buildRunning(-1, NotificationChannelIds.RUNNING_IN_BACKGROUND)
        helper.updateProgress(10)
        assertEquals(
            NotificationChannelIds.RUNNING_IN_BACKGROUND,
            shadowOf(nm).getNotification(MigrationTransferNotificationHelper.NOTIFICATION_ID_RUNNING)?.channelId,
        )

        helper.showTerminal("Done", "All moved")
        assertEquals(
            NotificationChannelIds.OTHER_ALERTS,
            shadowOf(nm).getNotification(MigrationTransferNotificationHelper.NOTIFICATION_ID_RESULT)?.channelId,
        )
    }

    @Test
    fun `the result notification is dropped when OTHER_ALERTS cannot be created`() {
        val capped = cappedNm()
        installNm(capped)

        MigrationTransferNotificationHelper(app).showTerminal("Done", "All moved")

        verify(exactly = 0) { capped.notify(any<Int>(), any()) }
    }
}
