package com.difft.android.call

import android.content.Context
import android.content.Intent
import com.difft.android.base.application.ScopeApplication
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.session.CallSessionLauncher
import com.difft.android.call.util.CallWaitDialogUtil
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import util.ScreenLockUtil
import kotlin.coroutines.CoroutineContext

/**
 * [CallJoinStarter.joinCall], the JOIN entry point behind incoming accept, critical-alert join and
 * chat join: the call page and its session are started through [CallSessionLauncher] from the
 * application context with the JOIN intent and this join's own wait-dialog claim. Only a launch
 * that went through lifts the app lock; a refused launch, or a join that fails before it, releases
 * only its own claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = CallJoinStarterTest.TestApplication::class, sdk = [33])
class CallJoinStarterTest {

    class TestApplication : ScopeApplication() {
        override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Unconfined
        override fun onCreate() {
            super.onCreate()
            ApplicationHelper.init(this)
        }
    }

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private val appContext = mockk<Context>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) {
        every { applicationContext } returns appContext
    }
    private val launcher = mockk<CallSessionLauncher>()
    private val launchedIntent = slot<Intent>()
    private val callDataManager = mockk<CallDataManager>(relaxed = true)

    @Before
    fun setUp() {
        ScreenLockUtil.temporarilyDisabled = false
        mockkObject(CallWaitDialogUtil, LCallManager)
        every { CallWaitDialogUtil.show(any()) } returns WAIT_TOKEN
        every { CallWaitDialogUtil.dismiss(any<Int>()) } just runs
        coEvery { LCallManager.checkQuicFeatureGrayStatus() } just runs
        every { launcher.launch(any(), capture(launchedIntent), any()) } returns true
    }

    @After
    fun tearDown() {
        ScreenLockUtil.temporarilyDisabled = false
        unmockkAll()
    }

    @Test
    fun `a join launches the page and its session with the JOIN intent and lifts the app lock for it`() = runTest(testDispatcher) {
        val ok = join(isNeedAppLock = true)

        assertTrue(ok)
        verify(exactly = 1) { launcher.launch(appContext, any(), WAIT_TOKEN) }
        val intent = CallIntent(launchedIntent.captured)
        assertEquals(CallIntent.Action.JOIN_CALL, intent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launchedIntent.captured.flags)
        assertEquals(CONVERSATION_ID, intent.conversationId)
        assertTrue(intent.needAppLock)
        assertTrue("the page knows the entry point shows the wait dialog", intent.callWaitDialogShown)
        assertTrue(ScreenLockUtil.temporarilyDisabled)
        verify(exactly = 1) { CallWaitDialogUtil.show(context) }
        verify(exactly = 0) { CallWaitDialogUtil.dismiss(any<Int>()) }
    }

    @Test
    fun `a refused launch releases only this join's claim and leaves the app lock alone`() = runTest(testDispatcher) {
        every { launcher.launch(any(), any(), any()) } returns false

        val ok = join()

        assertFalse(ok)
        verify(exactly = 1) { CallWaitDialogUtil.dismiss(WAIT_TOKEN) }
        assertFalse(ScreenLockUtil.temporarilyDisabled)
    }

    @Test
    fun `a join that fails before the launch releases its claim and launches nothing`() = runTest(testDispatcher) {
        every { callDataManager.getCallData(any()) } throws IllegalStateException("no call data")

        val ok = join()

        assertFalse(ok)
        verify(exactly = 0) { launcher.launch(any(), any(), any()) }
        verify(exactly = 1) { CallWaitDialogUtil.dismiss(WAIT_TOKEN) }
        assertFalse(ScreenLockUtil.temporarilyDisabled)
    }

    private suspend fun join(isNeedAppLock: Boolean = false): Boolean = CallJoinStarter(
        messageNotificationUtil = mockk(relaxed = true),
        dbRoomStore = mockk(relaxed = true),
        criticalAlertStateManager = mockk(relaxed = true),
        callDataManagerLazy = { callDataManager },
        sessionLauncher = { launcher },
    ).joinCall(context, ROOM_ID, "room", "caller", CallType.GROUP, CONVERSATION_ID, isNeedAppLock)

    private companion object {
        const val ROOM_ID = "r1"
        const val CONVERSATION_ID = "g1"
        const val WAIT_TOKEN = 7
    }
}
