package com.difft.android.app

import android.app.Application
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.storage.PendingLastUseTime
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import util.ScreenLockUtil
import java.lang.ref.WeakReference
import java.time.Duration

/**
 * [TempTalkApplication.isScreenLockPending] (#1355): set when a lock check is scheduled, kept across
 * the 3×150 ms retries that run outside the check job, cleared once the decision is applied. Same
 * harness as [TempTalkApplicationScreenLockTest]: the real class, constructed directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScreenLockPendingTest {

    private lateinit var app: TempTalkApplication
    private val userManager: UserManager = mockk(relaxed = true)
    private val pendingLastUseTime: PendingLastUseTime = mockk(relaxed = true)

    @Before
    fun setUp() {
        ScreenLockUtil.temporarilyDisabled = false
        app = TempTalkApplication()
        app.userManager = userManager
        app.onGoingCallStateManager = dagger.Lazy { OnGoingCallStateManager() }
        app.inComingCallStateManager = dagger.Lazy { InComingCallStateManager() }
        app.criticalAlertStateManager = dagger.Lazy { CriticalAlertStateManager() }
        app.pendingLastUseTime = pendingLastUseTime
        every { pendingLastUseTime.current() } returns 0L
        lockConfigured(true)
        setStartedActivityCount(1)
    }

    @After
    fun tearDown() {
        ScreenLockUtil.temporarilyDisabled = false
        unmockkAll()
    }

    private fun lockConfigured(on: Boolean) {
        every { userManager.getUserData() } returns UserData(
            baseAuth = "auth",
            passcode = if (on) "hash:salt" else null,
            passcodeTimeout = 0,
        )
    }

    private fun setResumedActivity(activity: FragmentActivity?) {
        val field = TempTalkApplication::class.java.getDeclaredField("currentResumedActivity")
        field.isAccessible = true
        field.set(app, activity?.let { WeakReference(it) })
    }

    private fun setStartedActivityCount(count: Int) {
        val field = TempTalkApplication::class.java.getDeclaredField("startedActivityCount")
        field.isAccessible = true
        field.setInt(app, count)
    }

    private fun scheduleQuickCheck() {
        val method = TempTalkApplication::class.java.getDeclaredMethod("scheduleQuickScreenLockCheck", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(app, false)
    }

    private fun invokePrivate(name: String) {
        val method = TempTalkApplication::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(app)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun resumedActivity(): FragmentActivity = mockk(relaxed = true) {
        every { isFinishing } returns false
        every { isDestroyed } returns false
        every { packageName } returns "com.difft.test"
    }

    @Test
    fun `pending until the lock is started`() {
        val activity = resumedActivity()
        setResumedActivity(activity)
        scheduleQuickCheck()
        assertTrue(app.isScreenLockPending())
        idle(50)
        assertTrue(app.isScreenLockPending())
        idle(100)
        verify(exactly = 1) { activity.startActivity(any()) }
        assertFalse(app.isScreenLockPending())
    }

    @Test
    fun `cleared when no lock is needed`() {
        lockConfigured(false)
        setResumedActivity(resumedActivity())
        scheduleQuickCheck()
        assertTrue(app.isScreenLockPending())
        idle(150)
        assertFalse(app.isScreenLockPending())
    }

    @Test
    fun `stays pending across the retries and clears when they give up`() {
        setResumedActivity(null)
        scheduleQuickCheck()
        idle(100) // first check: no activity, retry scheduled outside the check job
        assertTrue(app.isScreenLockPending())
        idle(300) // two retries
        assertTrue(app.isScreenLockPending())
        idle(600) // last retry gives up
        assertFalse(app.isScreenLockPending())
    }

    @Test
    fun `cleared when a retry finds an activity`() {
        setResumedActivity(null)
        scheduleQuickCheck()
        idle(100)
        assertTrue(app.isScreenLockPending())
        setResumedActivity(resumedActivity())
        idle(150)
        assertFalse(app.isScreenLockPending())
    }

    @Test
    fun `cleared when the app goes to the background`() {
        setResumedActivity(null)
        scheduleQuickCheck()
        assertTrue(app.isScreenLockPending())
        invokePrivate("onAppBackground")
        assertFalse(app.isScreenLockPending())
    }

    @Test
    fun `a flag whose clear was missed expires after five seconds`() {
        val pending = ScreenLockPending()
        pending.onCheckScheduled()
        idle(4_900)
        assertTrue(pending.state.value)
        idle(200)
        assertFalse(pending.state.value)
    }

    @Test
    fun `rescheduling restarts the expiry and a decision cancels it`() {
        val pending = ScreenLockPending()
        pending.onCheckScheduled()
        idle(4_000)
        pending.onCheckScheduled()
        idle(4_000)
        assertTrue("restarted at the second schedule", pending.state.value)
        idle(1_100)
        assertFalse(pending.state.value)

        pending.onCheckScheduled()
        pending.onDecided()
        pending.onCheckScheduled()
        idle(4_900)
        assertTrue(pending.state.value)
    }

    @Test
    fun `reading it does not consume the recent-unlock flag`() {
        ScreenLockUtil.markRecentlyUnlocked()
        app.isScreenLockPending()
        app.isScreenLockPending()
        // isScreenLockRequiredOrShowing consumes the flag once: it must still be there.
        assertFalse(app.isScreenLockRequiredOrShowing())
    }
}
