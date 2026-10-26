package com.difft.android.setting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationChannelIds
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.base.widget.ToastUtil
import dagger.hilt.EntryPoints
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
 * #1354, sideload upgrade: the retry tap and the in-app start both go through
 * [AppUpgradeService.start], which starts nothing without a foreground channel, and the service
 * promotes only on a verified one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = SideloadTestApplication::class, sdk = [34])
class AppUpgradeChannelTest {

    private lateinit var app: SideloadTestApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
        mockkObject(ToastUtil)
        every { ToastUtil.showLong(any<Int>()) } just runs
    }

    @After
    fun tearDown() {
        unmockkAll()
        NotificationChannelSpecs.resetVerifiedForegroundChannel()
    }

    private fun capNotificationChannels() {
        val capped = mockk<NotificationManager>(relaxed = true) {
            every { getNotificationChannel(any()) } returns null
            every { createNotificationChannel(any<NotificationChannel>()) } throws IllegalStateException("Limit exceed")
        }
        Shadow.extract<ShadowContextImpl>(app.baseContext).setSystemService(Context.NOTIFICATION_SERVICE, capped)
    }

    private fun retryIntent() = AppUpgradeRetryReceiver.intent(app, "https://x/a.apk", "h", "/data/a.apk", true)

    // The retry tap goes through the starter, which runs the channel preflight.
    @Test
    fun retryTap_startsTheServiceAfterThePreflight() {
        AppUpgradeRetryReceiver().onReceive(app, retryIntent())

        val started = shadowOf(app).nextStartedService
        assertEquals(AppUpgradeService::class.java.name, started?.component?.className)
        assertEquals("https://x/a.apk", started?.getStringExtra(AppUpgradeService.EXTRA_URL))
        assertEquals("h", started?.getStringExtra(AppUpgradeService.EXTRA_HASH))
        assertEquals("/data/a.apk", started?.getStringExtra(AppUpgradeService.EXTRA_PATH))
        assertTrue(started?.getBooleanExtra(AppUpgradeService.EXTRA_FORCE, false) == true)
        verify(exactly = 0) { ToastUtil.showLong(any<Int>()) }
        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, NotificationChannelSpecs.verifiedForegroundChannel)
    }

    // With no foreground channel the retry tap starts nothing.
    @Test
    fun retryTap_startsNothingWithoutAForegroundChannel() {
        capNotificationChannels()

        AppUpgradeRetryReceiver().onReceive(app, retryIntent())

        assertNull(shadowOf(app).nextStartedService)
        verify(exactly = 1) { ToastUtil.showLong(com.difft.android.R.string.status_upgrade_downolad_failed) }
    }

    @Test
    fun inAppStart_startsNothingWithoutAForegroundChannel() {
        capNotificationChannels()

        assertFalse(AppUpgradeService.start(app, "https://x/a.apk", "h", "/data/a.apk", false))
        assertNull(shadowOf(app).nextStartedService)
    }

    // The service promotes on a re-created RUNNING_IN_BACKGROUND channel.
    @Test
    fun service_promotesOnTheRunningInBackgroundChannel() {
        val posted = slot<Notification>()
        mockkStatic(ForegroundServiceStarter::class)
        every { ForegroundServiceStarter.startForegroundSafely(any(), any(), capture(posted), any()) } returns true
        mockService()

        val controller = Robolectric.buildService(AppUpgradeService::class.java, retryIntent()).create().startCommand(0, 1)
        awaitDownloadEnd()

        assertEquals(NotificationChannelIds.RUNNING_IN_BACKGROUND, posted.captured.channelId)
        controller.destroy()
    }

    // No channel → no startForeground; the failure is reported and the service stops.
    @Test
    fun service_stopsWithoutPromotingWithoutAChannel() {
        mockkStatic(ForegroundServiceStarter::class)
        mockService()
        capNotificationChannels()

        val controller = Robolectric.buildService(AppUpgradeService::class.java, retryIntent()).create().startCommand(0, 1)

        verify(exactly = 0) { ForegroundServiceStarter.startForegroundSafely(any(), any(), any(), any()) }
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    private fun mockService() {
        mockkStatic(EntryPoints::class)
        val entryPoint = mockk<AppUpgradeService.ServiceEntryPoint> {
            every { appUpgradeNotifier() } returns AppUpgradeNotifier(app)
            every { noHeaderHttpClient() } throws IllegalStateException("no network in tests")
        }
        every { EntryPoints.get(any(), AppUpgradeService.ServiceEntryPoint::class.java) } returns entryPoint
    }

    /** The download coroutine fails at once without a network; wait for its completion broadcast. */
    private fun awaitDownloadEnd() {
        val deadline = System.currentTimeMillis() + 5_000
        while (AppUpgradeService.isDownloading && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
