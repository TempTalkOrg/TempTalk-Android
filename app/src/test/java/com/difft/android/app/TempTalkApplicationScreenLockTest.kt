package com.difft.android.app

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.storage.PendingLastUseTime
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.login.ScreenLockActivity
import io.mockk.every
import io.mockk.mockk
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
import util.ScreenLockUtil
import java.lang.ref.WeakReference
import java.time.Duration

/**
 * Bug 3 — [TempTalkApplication.showScreenLockIfNeeded] bounded retry.
 *
 * The method reschedules itself (3×150ms via `launch(Dispatchers.Main) + delay`) when the app lock
 * is needed but no usable resumed Activity is available yet, instead of dropping the lock. It
 * self-terminates the moment the app is backgrounded (`startedActivityCount == 0`) so the retry
 * never escapes `lockCheckJob`'s effective scope.
 *
 * The retry is driven by the real Robolectric main looper (Dispatchers.Main → main Looper), advanced
 * deterministically via [ShadowLooper] `idleFor` — technology matches production. No TestDispatcher
 * override, so `delay` posts real delayed messages the shadow clock advances.
 *
 * Robolectric runs with a plain [Application] ([Config.application]) so it never instantiates the
 * real `@HiltAndroidApp` `TempTalkApplication` (whose `onCreate` does full DI/DB init). The instance
 * under test is constructed directly and its injected collaborators are set explicitly — `onCreate`
 * is never invoked. Private fields/methods are reached by reflection (no production test surface).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TempTalkApplicationScreenLockTest {

    private lateinit var app: TempTalkApplication

    private val userManager: UserManager = mockk(relaxed = true)
    // Real instances, not mocks: each exposes a StateFlow property (e.g. `isInForeground`) AND a
    // same-named convenience method (`isInForeground()`); MockK cannot reliably intercept the
    // colliding method, so a relaxed mock runs the real body against a null backing field and NPEs.
    // The @Inject no-arg constructors default every flag to false — exactly the "no call/alert"
    // baseline this suite needs; individual tests flip a flag via the public setters.
    private val onGoingCallStateManager = OnGoingCallStateManager()
    private val inComingCallStateManager = InComingCallStateManager()
    private val criticalAlertStateManager = CriticalAlertStateManager()
    private val pendingLastUseTime: PendingLastUseTime = mockk(relaxed = true)

    @Before
    fun setUp() {
        // No temporary exemption in effect (deadline 0 → getter reads false).
        ScreenLockUtil.temporarilyDisabled = false
        // Process-singleton — clear a recent-unlock window leaked by a sibling test so the popup
        // gate starts from a clean baseline.
        clearRecentlyUnlocked()

        app = TempTalkApplication()
        app.userManager = userManager
        app.onGoingCallStateManager = dagger.Lazy { onGoingCallStateManager }
        app.inComingCallStateManager = dagger.Lazy { inComingCallStateManager }
        app.criticalAlertStateManager = dagger.Lazy { criticalAlertStateManager }
        app.pendingLastUseTime = pendingLastUseTime

        // Drive shouldShowScreenLock() to TRUE: no call/alert in foreground (real instances default
        // to false), lock configured, authenticated, and immediate timeout (passcodeTimeout == 0).
        every { pendingLastUseTime.current() } returns 0L
        every { userManager.getUserData() } returns UserData(
            baseAuth = "auth",
            passcode = "hash:salt",
            passcodeTimeout = 0
        )
    }

    @After
    fun tearDown() {
        ScreenLockUtil.temporarilyDisabled = false
        clearRecentlyUnlocked()
        unmockkAll()
    }

    /** A finish-able, non-destroyed FragmentActivity that records `startActivity` intents. */
    private fun fakeResumedActivity(): FragmentActivity = mockk(relaxed = true) {
        every { isFinishing } returns false
        every { isDestroyed } returns false
        every { packageName } returns "com.difft.test"
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

    private fun invokeShowScreenLockIfNeeded(retriesLeft: Int) {
        val method = TempTalkApplication::class.java
            .getDeclaredMethod("showScreenLockIfNeeded", Int::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(app, retriesLeft)
    }

    private fun idleMainLooper(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun clearRecentlyUnlocked() {
        val field = ScreenLockUtil::class.java.getDeclaredField("recentlyUnlockedUntilElapsed")
        field.isAccessible = true
        (field.get(ScreenLockUtil) as java.util.concurrent.atomic.AtomicLong).set(0L)
    }

    // T6: popup gate — lock required (configured, authenticated, timed out) and not recently
    // unlocked → gate holds, so MainActivity.processIntent queues the deeplink instead of opening
    // the popup on top of the lock.
    @Test
    fun `T6 gate returns true when lock required`() {
        setStartedActivityCount(1)
        setResumedActivity(null)
        assert(app.isScreenLockRequiredOrShowing()) {
            "gate must hold when the lock is required"
        }
    }

    // T7: no-regression — no lock configured (empty passcode + pattern) → gate must NOT hold, so a
    // notification popup opens normally instead of being queued.
    @Test
    fun `T7 gate returns false when no lock is configured`() {
        every { userManager.getUserData() } returns UserData(
            baseAuth = "auth",
            passcode = null,
            pattern = null,
            passcodeTimeout = 0
        )
        setResumedActivity(null)
        assert(!app.isScreenLockRequiredOrShowing()) {
            "gate must pass through when no app lock is configured"
        }
    }

    // T12: telephony pass-through — an incoming/ongoing call in the foreground keeps the call screen
    // above the lock, so the gate must NOT hold (no deeplink queued, popup flow proceeds normally).
    @Test
    fun `T12 gate returns false during a foreground call`() {
        inComingCallStateManager.setIsInForeground(true)
        setResumedActivity(null)
        assert(!app.isScreenLockRequiredOrShowing()) {
            "gate must pass through while a call is in the foreground"
        }
    }

    // T16: blocking-fix regression pin — a lock-immediately user (passcodeTimeout == 0, set up
    // above) has shouldShowScreenLock == true even right after unlocking. markRecentlyUnlocked()
    // must make the gate return false so the replayed notification popup is NOT re-gated and can
    // finally open. Without the fix this returned true and the popup never opened.
    @Test
    fun `T16 recent unlock opens the gate for lock-immediately users`() {
        setResumedActivity(null)
        assert(app.isScreenLockRequiredOrShowing()) { "precondition: gate holds before unlock" }

        ScreenLockUtil.markRecentlyUnlocked()
        assert(!app.isScreenLockRequiredOrShowing()) {
            "a recent unlock must open the gate so the replayed popup opens"
        }
        // Consume-once: the bypass must not linger — the next gate check re-gates.
        assert(app.isScreenLockRequiredOrShowing()) {
            "recent-unlock bypass must be consumed after one check so it cannot linger"
        }
    }

    @Test
    fun `T10 null then activity available after 150ms eventually starts the lock`() {
        val activity = fakeResumedActivity()
        val startedIntent = slot<Intent>()
        every { activity.startActivity(capture(startedIntent)) } returns Unit

        setStartedActivityCount(1)
        setResumedActivity(null) // no resumed activity at the first hop

        invokeShowScreenLockIfNeeded(3)
        // Activity becomes available within the retry window.
        setResumedActivity(activity)
        idleMainLooper(150)

        verify(exactly = 1) { activity.startActivity(any()) }
        assert(startedIntent.captured.component?.className == ScreenLockActivity::class.java.name) {
            "retry must start ScreenLockActivity, was ${startedIntent.captured.component?.className}"
        }
    }

    @Test
    fun `T11 activity null through all retries gives up without crash or launch`() {
        setStartedActivityCount(1) // stays foreground the whole time
        setResumedActivity(null)

        invokeShowScreenLockIfNeeded(3)
        idleMainLooper(1000) // 1000ms >> 3×150ms: retries exhausted, chain terminated

        // Prove the chain is bounded: a valid activity appearing now is NOT acted on, because no
        // retry hop remains pending. A stray (unbounded) retry would fire within 150ms and launch.
        val activity = fakeResumedActivity()
        every { activity.startActivity(any()) } returns Unit
        setResumedActivity(activity)
        idleMainLooper(1000)

        verify(exactly = 0) { activity.startActivity(any()) }
    }

    @Test
    fun `T11b backgrounded mid-retry self-terminates with no launch and no orphan reschedule`() {
        setStartedActivityCount(1)
        setResumedActivity(null)

        invokeShowScreenLockIfNeeded(3) // posts the first retry hop
        setStartedActivityCount(0)      // app backgrounded before the hop fires
        idleMainLooper(150)             // hop runs, sees count == 0 → gives up, no reschedule

        // Prove no orphan reschedule escaped: make a valid activity available; if any stray retry
        // hop were still pending it would fire and launch the lock. It must not.
        val activity = fakeResumedActivity()
        every { activity.startActivity(any()) } returns Unit
        setResumedActivity(activity)
        idleMainLooper(1000)

        verify(exactly = 0) { activity.startActivity(any()) }
    }
}
