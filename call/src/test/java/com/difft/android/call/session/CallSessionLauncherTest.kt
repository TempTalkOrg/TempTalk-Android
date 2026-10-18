package com.difft.android.call.session

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.call.session.harness.CallSessionTestHarness
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.rules.GlobalStaticMockRule
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CallSessionLauncher], the call's entry point: the page launch comes first, then the session
 * start, with the launch tagged by the session's key; a live call refuses the launch; a page launch
 * that throws or a session that fails to start reports false and leaves no session; an accepted
 * call stops the incoming-call service; the session's app-lock requirement and wait-dialog claim
 * come from the entry point. Robolectric for the real `Intent` extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CallSessionLauncherTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val dispatcherRule = TestDispatcherRule(testDispatcher)

    @get:Rule(order = 1)
    val globalMocks = GlobalStaticMockRule()

    private lateinit var h: CallSessionTestHarness
    private val globalConfigs = mockk<GlobalConfigsManager>(relaxed = true) {
        every { getNewGlobalConfigs() } returns null
    }
    private val userManager = mockk<UserManager>(relaxed = true) { every { getUserData() } returns null }
    private val launched = mutableListOf<Intent>()
    private var currentAtLaunch: CallSession? = null
    private val context = mockk<Context>(relaxed = true) {
        every { startActivity(any()) } answers {
            currentAtLaunch = h.holder.current.value
            launched += firstArg<Intent>()
        }
    }
    private lateinit var launcher: CallSessionLauncher

    @Before
    fun setUp() {
        h = CallSessionTestHarness(testDispatcher)
        launcher = CallSessionLauncher(h.holder, globalConfigs, userManager)
        mockkObject(LCallManager)
        every { LCallManager.stopIncomingCallService(any(), any()) } just runs
    }

    @After
    fun tearDown() {
        h.endAll()
        unmockkAll()
    }

    @Test
    fun `a launch starts the page, then its session, and tags the page with the session key`() {
        val intent = intent(CallIntent.Action.START_CALL, CallRole.CALLER)

        val ok = launcher.launch(context, intent, WAIT_TOKEN)

        assertTrue(ok)
        assertNull("the page launch precedes the session start", currentAtLaunch)
        val session = checkNotNull(h.holder.current.value)
        val key = CallIntent(launched.single()).sessionKey
        assertTrue(!key.isNullOrEmpty())
        assertEquals(key, session.params.launchKey)
        assertEquals(WAIT_TOKEN, session.params.entryWaitToken)
        assertEquals(CallRole.CALLER, session.params.callRole)
        assertSame(intent, launched.single())
        verify(exactly = 1) { h.ongoingCallService.start(session.id) }
    }

    @Test
    fun `a launch while a call is live is refused with no page and no new session`() {
        assertTrue(launcher.launch(context, intent(CallIntent.Action.START_CALL, CallRole.CALLER), WAIT_TOKEN))
        val live = h.holder.current.value

        val second = launcher.launch(context, intent(CallIntent.Action.START_CALL, CallRole.CALLER), WAIT_TOKEN + 1)

        assertFalse(second)
        assertEquals(1, launched.size)
        assertEquals(1, h.sessions.size)
        assertSame(live, h.holder.current.value)
    }

    @Test
    fun `a page launch that throws leaves no session`() {
        every { context.startActivity(any()) } throws IllegalStateException("background start blocked")

        val ok = launcher.launch(context, intent(CallIntent.Action.JOIN_CALL, CallRole.CALLEE), WAIT_TOKEN)

        assertFalse(ok)
        assertTrue(h.sessions.isEmpty())
        assertNull(h.holder.current.value)
        verify(exactly = 0) { LCallManager.stopIncomingCallService(any(), any()) }
    }

    @Test
    fun `a session that fails to start reports false and leaves the holder free`() {
        every { h.components.proximity.start() } throws IllegalStateException("no sensor")

        val ok = launcher.launch(context, intent(CallIntent.Action.JOIN_CALL, CallRole.CALLEE), WAIT_TOKEN)

        assertFalse(ok)
        assertTrue(h.sessions.single().isEnded)
        assertNull(h.holder.current.value)
        verify(exactly = 0) { LCallManager.stopIncomingCallService(any(), any()) }
    }

    @Test
    fun `an accepted call stops the incoming-call service, an outgoing one does not`() {
        assertTrue(launcher.launch(context, intent(CallIntent.Action.START_CALL, CallRole.CALLER), WAIT_TOKEN))
        verify(exactly = 0) { LCallManager.stopIncomingCallService(any(), any()) }
        h.holder.current.value?.end(CallSessionEndReason.EXIT_CLEAR)

        assertTrue(launcher.launch(context, intent(CallIntent.Action.JOIN_CALL, CallRole.CALLEE), WAIT_TOKEN))

        verify(exactly = 1) { LCallManager.stopIncomingCallService(any(), any()) }
    }

    @Test
    fun `the app lock is required only when the user set one and the entry point asks for it`() {
        assertTrue(launcher.launch(context, intent(CallIntent.Action.JOIN_CALL, CallRole.CALLEE, needAppLock = true), WAIT_TOKEN))
        assertFalse("no lock set", checkNotNull(h.holder.current.value).params.needAppLock)
        assertFalse("seeded for the page-less window", h.ogsm.needAppLock)
        h.holder.current.value?.end(CallSessionEndReason.EXIT_CLEAR)

        every { userManager.getUserData() } returns UserData(pattern = "1234")
        assertTrue(launcher.launch(context, intent(CallIntent.Action.JOIN_CALL, CallRole.CALLEE, needAppLock = true), WAIT_TOKEN))

        val session = checkNotNull(h.holder.current.value)
        assertTrue(session.params.needAppLock)
        assertTrue(h.ogsm.needAppLock)
        assertNotNull(session.params.callConfig)
    }

    private fun intent(action: CallIntent.Action, role: CallRole, needAppLock: Boolean = false): Intent =
        CallIntent.Builder(ApplicationProvider.getApplicationContext(), LCallActivity::class.java)
            .withIntentFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .withAction(action)
            .withCallType(CallType.ONE_ON_ONE.type)
            .withCallRole(role.type)
            .withConversationId("conv-1")
            .withNeedAppLock(needAppLock)
            .withCallWaitDialogShown(true)
            .build()

    private companion object {
        const val WAIT_TOKEN = 7
    }
}
