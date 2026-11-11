package com.difft.android.chat.util

import android.app.NotificationManager
import android.content.Context
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.test.fakes.TestMessagingPause
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Message
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Optional





class MessageNotificationUtilMessagingPauseTest {

    private val messaging = TestMessagingPause().open()
    private val nm = mockk<NotificationManager>(relaxed = true)
    private val badge = mockk<AppIconBadgeManager>(relaxed = true)
    private val cache = mockk<NotificationCacheManager>(relaxed = true)
    private val forWhat = For.Account("+10001")
    private lateinit var context: Context
    private lateinit var util: MessageNotificationUtil

    private val message = mockk<Message>(relaxed = true).also {
        every { it.fromWho } returns For.Account("+10002")
        every { it.systemShowTimestamp } returns 1_700_000_000_000L
    }

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns nm
        mockkObject(ContactorUtil)

        val real = MessageNotificationUtil(
            context = context,
            userManager = mockk<UserManager>(relaxed = true),
            appIconBadgeManager = badge,
            activityProvider = mockk(relaxed = true),
            cacheManager = cache,
            criticalAlertManager = mockk(relaxed = true),
            onGoingCallStateManager = mockk(relaxed = true),
            groupUtil = mockk(relaxed = true),
            criticalAlertNotificationBuilder = mockk(relaxed = true),
            channelManager = mockk(relaxed = true),
            repostGuard = mockk(relaxed = true),
            criticalAlertCallGate = mockk(relaxed = true),
            groupNotifyGate = mockk(relaxed = true),
            messagingGate = messaging.gate,
        )
        
        util = spyk(real, recordPrivateCalls = true)
        coEvery { util["shouldInterceptNotification"](any<For>(), any<Message>(), any<NotificationDelivery>()) } returns false
    }

    @After
    fun tearDown() = unmockkAll()

    private fun assertNothingPosted() {
        verify(exactly = 0) { nm.notify(any(), any()) }
        verify(exactly = 0) { badge.updateAppIconBadgeNum(any()) }
        verify(exactly = 0) { cache.addMessage(any(), any()) }
    }

    @Test
    fun `T29 a notification scheduled before the pause and run after it is not posted`() = runBlocking {
        messaging.pauseMessaging()

        util.showNotificationSuspend(context, message, forWhat)

        assertNothingPosted()
        io.mockk.coVerify(exactly = 0) { ContactorUtil.getContactWithID(any(), any()) }
    }

    @Test
    fun `T32 a pause that begins during the contact lookup suppresses the notification`() = runBlocking {
        coEvery { ContactorUtil.getContactWithID(any(), any()) } answers {
            messaging.pauseMessaging()
            Optional.empty()
        }

        util.showNotificationSuspend(context, message, forWhat)

        io.mockk.coVerify(exactly = 1) { ContactorUtil.getContactWithID(any(), any()) }
        assertNothingPosted()
    }

    @Test
    fun `the push fallback notification is not posted while paused`() = runBlocking {
        messaging.pauseMessaging()

        util.showNotificationOfPush(context, forWhat)

        verify(exactly = 0) { nm.notify(any(), any()) }
        verify(exactly = 0) { nm.activeNotifications }
    }

    @Test
    fun `an incoming-call notification whose lookup outlasts the pause boundary is not posted`() = runBlocking {
        
        ApplicationHelper.init(mockk<ScopeApplication>(relaxed = true))
        mockkObject(ResUtils)
        every { ResUtils.getString(any()) } returns ""
        coEvery { ContactorUtil.getContactWithID(any(), any()) } answers {
            messaging.pauseMessaging()
            Optional.empty()
        }
        val roomId = "room-1"

        util.showCallNotificationNew(roomId, "name", "+10002", null, CallType.ONE_ON_ONE, needAppLock = false)
        withTimeout(5_000) { while (util.hasPendingCallNotification(roomId.hashCode())) delay(10) }

        io.mockk.coVerify(exactly = 1) { ContactorUtil.getContactWithID(any(), any()) }
        
        verify(exactly = 0) { ResUtils.getString(any()) }
        verify(exactly = 0) { nm.notify(any(), any()) }
    }
}
