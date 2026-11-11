package com.difft.android.app

import android.app.Application
import android.util.Base64
import com.difft.android.IndexActivity
import com.difft.android.MainActivity
import com.difft.android.base.storage.PendingLastUseTime
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.InComingCallStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.login.ScreenLockActivity
import com.difft.android.network.auth.AuthTokenProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import util.ScreenLockUtil









@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TempTalkApplicationNotificationPopupGateTest {

    private lateinit var app: TempTalkApplication

    private val userManager: UserManager = mockk(relaxed = true)
    private val authTokenProvider: AuthTokenProvider = mockk(relaxed = true)
    private val pendingLastUseTime: PendingLastUseTime = mockk(relaxed = true)
    private val onGoingCallStateManager = OnGoingCallStateManager()
    private val inComingCallStateManager = InComingCallStateManager()

    private val account = "+1234567"
    private val token = jwtWithUid(account)

    @Before
    fun setUp() {
        ScreenLockUtil.temporarilyDisabled = false
        clearRecentlyUnlocked()

        app = TempTalkApplication()
        app.userManager = userManager
        app.authTokenProvider = dagger.Lazy { authTokenProvider }
        app.onGoingCallStateManager = dagger.Lazy { onGoingCallStateManager }
        app.inComingCallStateManager = dagger.Lazy { inComingCallStateManager }
        app.criticalAlertStateManager = dagger.Lazy { CriticalAlertStateManager() }
        app.pendingLastUseTime = pendingLastUseTime

        every { pendingLastUseTime.current() } returns System.currentTimeMillis()
        every { authTokenProvider.peek() } returns token
        
        every { userManager.getUserData() } returns UserData(account = account, baseAuth = "auth", microToken = token)
        addIndexActivity(finishing = false)
        
        setField("foregroundUnlocked", true)
    }

    @After
    fun tearDown() {
        ScreenLockUtil.temporarilyDisabled = false
        clearRecentlyUnlocked()
        unmockkAll()
    }

    @Test
    fun `opens in place with a home screen, a valid session and no lock`() {
        assertNull(app.inPlacePopupBlocker())
        assertFalse(app.isMainActivityHostingRecovery())
    }

    @Test
    fun `blocked without a home screen`() {
        clearSet("liveIndexActivities")
        assertEquals("no_home", app.inPlacePopupBlocker())
    }

    @Test
    fun `a finishing home screen does not count`() {
        clearSet("liveIndexActivities")
        addIndexActivity(finishing = true)
        assertEquals("no_home", app.inPlacePopupBlocker())
    }

    @Test
    fun `a routing MainActivity sends the tap through MainActivity without dropping it`() {
        addMainActivity(finishing = false, hostsLockScreen = false)
        assertFalse(app.isMainActivityHostingRecovery())
        assertEquals("main_alive", app.inPlacePopupBlocker())
    }

    @Test
    fun `a MainActivity on its recovery screen drops the tap`() {
        addMainActivity(finishing = false, hostsLockScreen = true)
        assertTrue(app.isMainActivityHostingRecovery())
        assertEquals("main_alive", app.inPlacePopupBlocker())
    }

    @Test
    fun `a finishing MainActivity neither routes nor recovers`() {
        addMainActivity(finishing = true, hostsLockScreen = true)
        assertFalse(app.isMainActivityHostingRecovery())
        assertNull(app.inPlacePopupBlocker())
    }

    @Test
    fun `blocked once a passive logout cleared the session`() {
        every { userManager.getUserData() } returns UserData(account = account, baseAuth = null, microToken = null)
        every { authTokenProvider.peek() } returns null
        assertEquals("session", app.inPlacePopupBlocker())
    }

    @Test
    fun `blocked when the token belongs to another account`() {
        every { authTokenProvider.peek() } returns jwtWithUid("+7654321")
        assertEquals("session", app.inPlacePopupBlocker())
    }

    @Test
    fun `an unlocked user with lock immediately is not asked to unlock again on either route`() {
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)

        assertNull(app.inPlacePopupBlocker())
        
        assertFalse(app.isScreenLockRequiredOrShowing())
    }

    @Test
    fun `without a confirmed unlock both gates fall back to the timeout check`() {
        setField("foregroundUnlocked", false)
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)

        assertEquals("lock_unconfirmed", app.inPlacePopupBlocker())
        assertTrue(app.isScreenLockRequiredOrShowing())
    }

    @Test
    fun `a lock check skipped for a call in front leaves the stay unconfirmed`() {
        setField("foregroundUnlocked", false)
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)
        onGoingCallStateManager.setIsInForeground(true)

        invokeShowScreenLockIfNeeded(retriesLeft = 3)

        assertEquals("lock_unconfirmed", app.inPlacePopupBlocker())
    }

    @Test
    fun `an unlocked stay is not trusted while a call exists`() {
        
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)
        val inCalling = OnGoingCallStateManager::class.java.getDeclaredField("_isInCalling")
        inCalling.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (inCalling.get(onGoingCallStateManager) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = true

        assertEquals("lock_unconfirmed", app.inPlacePopupBlocker())
        assertTrue(app.isScreenLockRequiredOrShowing())
    }

    @Test
    fun `an unlocked stay is not trusted while an incoming call screen shows`() {
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)
        inComingCallStateManager.setIsActivityShowing(true)

        assertEquals("lock_unconfirmed", app.inPlacePopupBlocker())
    }

    @Test
    fun `a lock check that gave up retrying leaves the stay unconfirmed`() {
        setField("foregroundUnlocked", false)
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)

        
        invokeShowScreenLockIfNeeded(retriesLeft = 0)

        assertEquals("lock_unconfirmed", app.inPlacePopupBlocker())
    }

    @Test
    fun `a lock check that finds no lock due confirms the stay`() {
        setField("foregroundUnlocked", false)
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 300)

        invokeShowScreenLockIfNeeded(retriesLeft = 3)

        assertNull(app.inPlacePopupBlocker())
    }

    @Test
    fun `unlocking confirms the stay and going to the background clears it`() {
        setField("foregroundUnlocked", false)
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)

        app.onAppUnlocked()
        assertNull(app.inPlacePopupBlocker())

        val onAppBackground = TempTalkApplication::class.java.getDeclaredMethod("onAppBackground")
        onAppBackground.isAccessible = true
        onAppBackground.invoke(app)
        assertEquals("lock_unconfirmed", app.inPlacePopupBlocker())
    }

    @Test
    fun `blocked while the lock page is alive even though it is paused`() {
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)
        addScreenLockActivity(finishing = false)
        assertEquals("lock_shown", app.inPlacePopupBlocker())
    }

    @Test
    fun `a lock page that is finishing after unlock does not block`() {
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)
        addScreenLockActivity(finishing = true)
        assertNull(app.inPlacePopupBlocker())
    }

    @Test
    fun `blocked while a lock is owed`() {
        setField("lockCheckDeferred", true)
        assertEquals("lock_pending", app.inPlacePopupBlocker())
    }

    @Test
    fun `blocked while the foreground lock check has not decided yet`() {
        setField("lockCheckJob", Job())
        assertEquals("lock_pending", app.inPlacePopupBlocker())
    }

    @Test
    fun `blocked while a lock decision waits for a retry outside the check job`() {
        val pending = TempTalkApplication::class.java.getDeclaredField("screenLockPending")
        pending.isAccessible = true
        (pending.get(app) as ScreenLockPending).onCheckScheduled()

        assertEquals("lock_pending", app.inPlacePopupBlocker())
    }

    @Test
    fun `leaves the one-shot unlock bypass armed for the replay it belongs to`() {
        every { userManager.getUserData() } returns lockedUser(passcodeTimeout = 0)
        ScreenLockUtil.markRecentlyUnlocked()

        app.inPlacePopupBlocker()
        
        assertFalse(app.isScreenLockRequiredOrShowing())
    }

    private fun lockedUser(passcodeTimeout: Int) = UserData(
        account = account,
        baseAuth = "auth",
        microToken = token,
        passcode = "hash:salt",
        passcodeTimeout = passcodeTimeout,
    )

    @Suppress("UNCHECKED_CAST")
    private fun addIndexActivity(finishing: Boolean) {
        val field = TempTalkApplication::class.java.getDeclaredField("liveIndexActivities")
        field.isAccessible = true
        val activity = mockk<IndexActivity>(relaxed = true) { every { isFinishing } returns finishing }
        (field.get(app) as MutableSet<IndexActivity>).add(activity)
    }

    private fun clearSet(name: String) {
        val field = TempTalkApplication::class.java.getDeclaredField(name)
        field.isAccessible = true
        (field.get(app) as MutableSet<*>).clear()
    }

    private fun invokeShowScreenLockIfNeeded(retriesLeft: Int) {
        val method = TempTalkApplication::class.java
            .getDeclaredMethod("showScreenLockIfNeeded", Int::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(app, retriesLeft)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addMainActivity(finishing: Boolean, hostsLockScreen: Boolean) {
        val field = TempTalkApplication::class.java.getDeclaredField("liveMainActivities")
        field.isAccessible = true
        val activity = mockk<MainActivity>(relaxed = true) {
            every { isFinishing } returns finishing
            every { this@mockk.hostsLockScreen } returns hostsLockScreen
        }
        (field.get(app) as MutableSet<MainActivity>).add(activity)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addScreenLockActivity(finishing: Boolean) {
        val field = TempTalkApplication::class.java.getDeclaredField("liveScreenLockActivities")
        field.isAccessible = true
        val activity = mockk<ScreenLockActivity>(relaxed = true) { every { isFinishing } returns finishing }
        (field.get(app) as MutableSet<ScreenLockActivity>).add(activity)
    }

    private fun setField(name: String, value: Any?) {
        val field = TempTalkApplication::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(app, value)
    }

    private fun clearRecentlyUnlocked() {
        val field = ScreenLockUtil::class.java.getDeclaredField("recentlyUnlockedUntilElapsed")
        field.isAccessible = true
        (field.get(ScreenLockUtil) as java.util.concurrent.atomic.AtomicLong).set(0L)
    }

    private fun jwtWithUid(uid: String): String {
        fun encode(json: String) =
            Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return "${encode("""{"alg":"none"}""")}.${encode("""{"uid":"$uid"}""")}.sig"
    }
}
