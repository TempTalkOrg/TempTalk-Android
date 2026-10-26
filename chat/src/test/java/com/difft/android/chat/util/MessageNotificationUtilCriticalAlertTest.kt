package com.difft.android.chat.util

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.call.VoiceRecordingTracker
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.NotificationRepostGuard
import com.difft.android.call.LCallManager
import com.difft.android.call.manager.CriticalAlertManager
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertRoomRegistry
import com.difft.android.call.state.OnGoingCallStateManager
import difft.android.messageserialization.For
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Before
import org.junit.Test
import util.AppForegroundObserver

/**
 * Branch coverage for [MessageNotificationUtil.showCriticalAlert]: the foreground branch posts a
 * silent companion notification before launching CriticalAlertActivity, the background branch
 * posts the full-screen notification and plays sound only after a successful notify, and the
 * cache-hit / already-showing / concurrent-add branches stay no-ops, and an alert for the call
 * being joined or already active (#1381) is only recorded as processed.
 */
class MessageNotificationUtilCriticalAlertTest {

    private val conversationId = "+10001"
    private val alertTitle = "Alert"
    private val alertContent = "Server down"
    private val timestamp = 1_700_000_000_123L
    private val notificationId = timestamp.hashCode()
    private val forWhat = For.Account(conversationId)

    private lateinit var nm: NotificationManager
    private lateinit var criticalAlertManager: CriticalAlertManager
    private lateinit var builder: CriticalAlertNotificationBuilder
    private lateinit var notification: Notification
    private lateinit var repostGuard: NotificationRepostGuard
    private lateinit var callGate: CriticalAlertCallGate
    private val registry = CriticalAlertRoomRegistry()
    private lateinit var util: MessageNotificationUtil

    @Before
    fun setUp() {
        // LCallManager's <clinit> reads ApplicationHelper.instance (lateinit).
        ApplicationHelper.init(mockk<ScopeApplication>(relaxed = true))
        mockkObject(LCallManager)
        // isForegrounded() is @JvmStatic, so callers hit the static method.
        mockkStatic(AppForegroundObserver::class)
        every { AppForegroundObserver.isForegrounded() } returns true
        // isRecording is a @Volatile property read; it is not interceptable by mockkObject, so drive the real flag.
        VoiceRecordingTracker.setRecording(false, "test")
        every { LCallManager.dismissIncomingNotification(any()) } just Runs
        every { LCallManager.dismissIncomingNotificationByRoomId(any()) } just Runs

        nm = mockk(relaxed = true)
        every { nm.activeNotifications } returns emptyArray()
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm

        criticalAlertManager = mockk(relaxed = true)
        every { criticalAlertManager.isCriticalAlertNotificationProcessed(any(), any()) } returns false
        every { criticalAlertManager.addCriticalAlertNotificationIfNotExists(any(), any()) } returns true

        notification = mockk()
        builder = mockk()
        every { builder.build(any(), any(), any()) } returns notification

        repostGuard = mockk(relaxed = true)
        callGate = CriticalAlertCallGate(OnGoingCallStateManager(), registry)

        util = MessageNotificationUtil(
            context = context,
            userManager = mockk(relaxed = true),
            appIconBadgeManager = mockk(relaxed = true),
            activityProvider = mockk(relaxed = true),
            cacheManager = mockk(relaxed = true),
            criticalAlertManager = criticalAlertManager,
            onGoingCallStateManager = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
            criticalAlertNotificationBuilder = builder,
            channelManager = mockk(relaxed = true),
            repostGuard = repostGuard,
            criticalAlertCallGate = callGate,
        )
    }

    @After
    fun tearDown() {
        VoiceRecordingTracker.setRecording(false, "test")
        unmockkAll()
    }

    private fun show(roomId: String? = "r1") =
        util.showCriticalAlert(forWhat, alertTitle, alertContent, timestamp, roomId)

    private fun payload(roomId: String? = "r1") =
        CriticalAlertPayload(conversationId, alertTitle, alertContent, notificationId, timestamp, roomId)

    // N1
    @Test
    fun `foreground posts silent companion then launches activity`() {
        show()

        verify(exactly = 1) { builder.build(payload(), "CRITICAL_ALERT_V2", CriticalAlertPresentation.SILENT_COMPANION) }
        verify(exactly = 0) { builder.build(any(), any(), CriticalAlertPresentation.FULL_SCREEN) }
        verifyOrder {
            repostGuard.onPosted(notificationId)
            nm.notify(notificationId, notification)
            criticalAlertManager.startCriticalAlertActivity(conversationId, alertTitle, alertContent, notificationId, "r1")
        }
        verify(exactly = 0) { repostGuard.repostIfActive(any()) }
        verify(exactly = 1) { nm.notify(any(), any()) }
        verify(exactly = 1) { criticalAlertManager.startCriticalAlertActivity(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { criticalAlertManager.playSoundAndFlashLight(any(), any(), any()) }
    }

    // N2
    @Test
    fun `foreground notify failure still launches activity`() {
        every { nm.notify(any(), any()) } throws RuntimeException("boom")

        show()

        verify(exactly = 1) {
            criticalAlertManager.startCriticalAlertActivity(conversationId, alertTitle, alertContent, notificationId, "r1")
        }
        verify(exactly = 0) { criticalAlertManager.playSoundAndFlashLight(any(), any(), any()) }
    }

    // N3
    @Test
    fun `background posts full screen notification and plays sound`() {
        every { AppForegroundObserver.isForegrounded() } returns false

        show()

        assertBackgroundPath()
    }

    // N4
    @Test
    fun `foreground while recording falls back to full screen notification`() {
        VoiceRecordingTracker.setRecording(true, "test")

        show()

        assertBackgroundPath()
    }

    // N5
    @Test
    fun `background notify failure skips sound`() {
        every { AppForegroundObserver.isForegrounded() } returns false
        every { nm.notify(any(), any()) } throws RuntimeException("boom")

        show()

        verify(exactly = 0) { criticalAlertManager.playSoundAndFlashLight(any(), any(), any()) }
        verify(exactly = 0) { criticalAlertManager.startCriticalAlertActivity(any(), any(), any(), any(), any()) }
    }

    // N6
    @Test
    fun `cache hit is a no-op whether or not the alert is still showing`() {
        listOf(true, false).forEach { showing ->
            every { criticalAlertManager.isCriticalAlertNotificationProcessed(conversationId, notificationId) } returns true
            every { criticalAlertManager.isCriticalAlertShowing(any()) } returns showing

            show()

            verify(exactly = 0) { nm.notify(any(), any()) }
            verify(exactly = 0) { builder.build(any(), any(), any()) }
            verify(exactly = 0) { criticalAlertManager.startCriticalAlertActivity(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { criticalAlertManager.addCriticalAlertNotificationIfNotExists(any(), any()) }
        }
    }

    // #1347: a late duplicate FCM for an already-processed alert goes through the repost guard,
    // which decides (active, not cancelled in-process) whether to refresh it.
    @Test
    fun `cache hit asks the guard to re-post the alert`() {
        every { criticalAlertManager.isCriticalAlertNotificationProcessed(conversationId, notificationId) } returns true

        show()

        verify(exactly = 1) { repostGuard.repostIfActive(notificationId) }
        verify(exactly = 0) { repostGuard.onPosted(any()) }
    }

    @Test
    fun `fresh alert does not ask the guard to re-post`() {
        every { AppForegroundObserver.isForegrounded() } returns false

        show()

        verify(exactly = 0) { repostGuard.repostIfActive(any()) }
        verify(exactly = 1) { repostGuard.onPosted(notificationId) }
    }

    @Test
    fun `cancelling a call notification goes through the guard`() {
        util.cancelNotificationsById(notificationId)

        verify(exactly = 1) { repostGuard.cancel(notificationId) }
        verify(exactly = 0) { nm.cancel(any<Int>()) }
    }

    // N7
    @Test
    fun `already showing records the id in cache and asks the guard to re-post`() {
        val sbn = mockk<StatusBarNotification>()
        every { sbn.id } returns notificationId
        every { nm.activeNotifications } returns arrayOf(sbn)

        show()

        verify(exactly = 1) { criticalAlertManager.addCriticalAlertNotification(conversationId, notificationId) }
        verify(exactly = 1) { repostGuard.repostIfActive(notificationId) }
        verify(exactly = 0) { nm.notify(any(), any()) }
        verify(exactly = 0) { criticalAlertManager.startCriticalAlertActivity(any(), any(), any(), any(), any()) }
    }

    // N8
    @Test
    fun `concurrent add failure is a no-op`() {
        every { criticalAlertManager.addCriticalAlertNotificationIfNotExists(any(), any()) } returns false

        show()

        verify(exactly = 0) { nm.notify(any(), any()) }
        verify(exactly = 0) { criticalAlertManager.startCriticalAlertActivity(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { LCallManager.dismissIncomingNotification(any()) }
        verify(exactly = 0) { LCallManager.dismissIncomingNotificationByRoomId(any()) }
    }

    // N9
    @Test
    fun `incoming call notification is dismissed by room id before notify`() {
        show(roomId = "r1")

        verifyOrder {
            LCallManager.dismissIncomingNotificationByRoomId("r1")
            nm.notify(notificationId, notification)
        }
        verify(exactly = 0) { LCallManager.dismissIncomingNotification(any()) }
    }

    // N9
    @Test
    fun `incoming call notification is dismissed by conversation id when room id is null`() {
        show(roomId = null)

        verifyOrder {
            LCallManager.dismissIncomingNotification(conversationId)
            nm.notify(notificationId, notification)
        }
        verify(exactly = 0) { LCallManager.dismissIncomingNotificationByRoomId(any()) }
        verify(exactly = 1) { builder.build(payload(roomId = null), any(), CriticalAlertPresentation.SILENT_COMPANION) }
    }

    // #1381
    @Test
    fun `alert for the joining call is recorded as processed and shows nothing`() {
        callGate.beginJoin("r1", conversationId)
        listOf(true, false).forEach { foreground ->
            every { AppForegroundObserver.isForegrounded() } returns foreground

            show()

            verify { criticalAlertManager.addCriticalAlertNotificationIfNotExists(conversationId, notificationId) }
            assertNothingPublished()
            verify(exactly = 0) { LCallManager.dismissIncomingNotificationByRoomId(any()) }
            verify(exactly = 0) { LCallManager.dismissIncomingNotification(any()) }
        }
    }

    // #1381: a join that begins while the alert is being published may have cancelled alerts
    // before the post; the publisher re-checks and undoes it.
    @Test
    fun `a join that begins during the post undoes it`() {
        listOf(true, false).forEach { foreground ->
            every { AppForegroundObserver.isForegrounded() } returns foreground
            var token = 0L
            every { nm.notify(notificationId, notification) } answers { token = callGate.beginJoin("r1", conversationId) }

            show()

            verifyOrder {
                nm.notify(notificationId, notification)
                repostGuard.cancel(notificationId)
                criticalAlertManager.stopSoundIfMatch(notificationId)
            }
            callGate.endJoin(token)
        }
    }

    @Test
    fun `a published alert is not undone while no join began`() {
        show()

        verify(exactly = 0) { repostGuard.cancel(any()) }
        verify(exactly = 0) { criticalAlertManager.stopSoundIfMatch(any()) }
    }

    @Test
    fun `alert for another call is shown`() {
        callGate.beginJoin("r2", conversationId)

        show(roomId = "r1")

        verify(exactly = 1) { criticalAlertManager.startCriticalAlertActivity(conversationId, alertTitle, alertContent, notificationId, "r1") }
    }

    // #1387
    @Test
    fun `alert for an ended room shows nothing and is not recorded`() {
        registry.end("r1")
        listOf(true, false).forEach { foreground ->
            every { AppForegroundObserver.isForegrounded() } returns foreground

            show()

            verify { criticalAlertManager.addCriticalAlertNotificationIfNotExists(conversationId, notificationId) }
            assertNothingPublished()
            verify(exactly = 0) { criticalAlertManager.recordRoomAlert(any(), any()) }
            verify(exactly = 0) { LCallManager.dismissIncomingNotificationByRoomId(any()) }
        }
    }

    // #1387: a call end that lands while the alert is being published is caught by the re-check.
    @Test
    fun `a room that ends during the foreground post undoes it`() {
        assertEndDuringPostUndone(foreground = true)
    }

    @Test
    fun `a room that ends during the background post undoes it`() {
        assertEndDuringPostUndone(foreground = false)
    }

    private fun assertEndDuringPostUndone(foreground: Boolean) {
        every { AppForegroundObserver.isForegrounded() } returns foreground
        every { nm.notify(notificationId, notification) } answers { registry.end("r1") }

        show()

        verifyOrder {
            nm.notify(notificationId, notification)
            repostGuard.cancel(notificationId)
            criticalAlertManager.stopSoundIfMatch(notificationId)
        }
    }

    @Test
    fun `a published alert with a room is recorded before it is posted`() {
        show(roomId = "r1")

        verifyOrder {
            criticalAlertManager.recordRoomAlert("r1", notificationId)
            nm.notify(notificationId, notification)
        }
    }

    @Test
    fun `an alert without a room id is never recorded`() {
        show(roomId = null)
        show(roomId = "")

        verify(exactly = 0) { criticalAlertManager.recordRoomAlert(any(), any()) }
    }

    private fun assertNothingPublished() {
        verify(exactly = 0) { builder.build(any(), any(), any()) }
        verify(exactly = 0) { nm.notify(any(), any()) }
        verify(exactly = 0) { repostGuard.onPosted(any()) }
        verify(exactly = 0) { criticalAlertManager.startCriticalAlertActivity(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { criticalAlertManager.playSoundAndFlashLight(any(), any(), any()) }
    }

    private fun assertBackgroundPath() {
        verify(exactly = 1) { builder.build(payload(), "CRITICAL_ALERT_V2", CriticalAlertPresentation.FULL_SCREEN) }
        verify(exactly = 0) { builder.build(any(), any(), CriticalAlertPresentation.SILENT_COMPANION) }
        verifyOrder {
            repostGuard.onPosted(notificationId)
            nm.notify(notificationId, notification)
            criticalAlertManager.playSoundAndFlashLight(conversationId, notificationId, "r1")
        }
        verify(exactly = 1) { nm.notify(any(), any()) }
        verify(exactly = 0) { criticalAlertManager.startCriticalAlertActivity(any(), any(), any(), any(), any()) }
    }
}
