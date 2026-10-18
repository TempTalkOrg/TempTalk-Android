package com.difft.android.call.manager

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.call.LCallToChatController
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.state.OgsmTestState
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.util.ScreenDeviceUtil
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import util.ScreenLockUtil

/**
 * An incoming call while this device is in a call: [IncomingCallServiceManager] reads presence from
 * `OnGoingCallStateManager` and rings through the notification instead of starting the incoming
 * page over the live call. The same call with no call up starts the page (the control case).
 * Row T5-16.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class IncomingCallServiceManagerInCallTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ogsm = OnGoingCallStateManager()
    private val chat = mockk<LCallToChatController>(relaxed = true) {
        every { isIncomingCallActivityShowing() } returns false
        every { isAppForegrounded() } returns true
    }
    private lateinit var manager: IncomingCallServiceManager

    @Before
    fun setUp() {
        mockkObject(ScreenDeviceUtil)
        every { ScreenDeviceUtil.wakeUpDevice() } just Runs
        every { globalMocks.mockGlobalServices.activityProvider.getActivityClass(any()) } returns Activity::class.java
        manager = IncomingCallServiceManager(
            callToChatController = chat,
            criticalAlertManager = mockk(relaxed = true) { every { isCriticalAlertShowing(any()) } returns false },
            onGoingCallStateManager = ogsm,
            callDataManager = CallDataManager(),
            ringtoneManager = mockk(relaxed = true),
            vibrationManager = mockk(relaxed = true),
            callTimeoutManager = mockk(relaxed = true),
            ringtoneSource = mockk(relaxed = true),
            messageNotificationUtil = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        ScreenLockUtil.temporarilyDisabled = false
        unmockkAll()
    }

    @Test
    fun `T5-16 during a call the incoming call rings through the notification, not the page`() {
        OgsmTestState.enterCall(ogsm, "r1")

        manager.startIncomingCallService(app, incomingIntent())

        assertNull(shadowOf(app).nextStartedActivity)
        verify(exactly = 1) { chat.showCallNotification("r9", any(), any(), any(), CallType.ONE_ON_ONE, true) }
    }

    @Test
    fun `T5-16 control - with no call up the incoming page is started`() {
        manager.startIncomingCallService(app, incomingIntent())

        assertEquals(Activity::class.java.name, shadowOf(app).nextStartedActivity?.component?.className)
        verify(exactly = 0) { chat.showCallNotification(any(), any(), any(), any(), any(), any()) }
    }

    private fun incomingIntent() = Intent()
        .putExtra(LCallConstants.BUNDLE_KEY_ROOM_ID, "r9")
        .putExtra(LCallConstants.BUNDLE_KEY_CALL_TYPE, CallType.ONE_ON_ONE.type)
        .putExtra(LCallConstants.BUNDLE_KEY_CALLER_ID, "+10002")
        .putExtra(LCallConstants.BUNDLE_KEY_CALL_NAME, "Bob")
}
