package com.difft.android.chat.messages

import android.app.AlarmManager
import android.content.Context
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.chat.websocket.monitor.WebSocketHealthMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import util.AppForegroundObserver
import java.time.Duration

/**
 * Tests for [MessageServiceManager.ensureAlarmChainAlive] — the alarm-chain
 * self-healing added for vendor ROMs that swallow alarm broadcasts (issue #1157).
 *
 * The chain is one-shot + re-arm-on-trigger; one swallowed broadcast kills it.
 * Healing must re-arm ONLY when keep-alive is enabled AND the chain has been
 * silent past the stale window for the active alarm mode: 4x alarm interval
 * (20 min) with exact-alarm permission, 2h + interval without (Doze legitimately
 * defers inexact delivery 30 min - 2 hr).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MessageServiceManagerAlarmChainTest {

    private lateinit var context: Context
    private lateinit var shadowAlarmManager: ShadowAlarmManager
    private lateinit var userManager: UserManager
    private lateinit var userData: UserData
    private lateinit var manager: MessageServiceManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = shadowOf(alarmManager)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        userData = mockk(relaxed = true)
        userManager = mockk(relaxed = true)
        every { userManager.getUserData() } returns userData

        val monitor = mockk<WebSocketHealthMonitor>(relaxed = true)
        every { monitor.monitorWakeTicks } returns MutableSharedFlow()

        manager = MessageServiceManager(context, userManager, monitor)
    }

    @After
    fun tearDown() {
        // The manager's init registers a listener on the global AppForegroundObserver
        // object with no removal path; clear it so leaked listeners bound to cleared
        // mocks cannot fire into unrelated tests sharing the Robolectric sandbox.
        val listenersField = AppForegroundObserver::class.java.getDeclaredField("listeners")
        listenersField.isAccessible = true
        (listenersField.get(AppForegroundObserver) as MutableSet<*>).clear()
        unmockkAll()
    }

    private fun advanceMinutes(minutes: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(minutes))
    }

    @Test
    fun `ensure does nothing when keep-alive disabled even if stale`() {
        every { userData.keepAliveEnabled } returns false
        advanceMinutes(21)

        manager.ensureAlarmChainAlive("test")

        assertNull("must not arm alarm when keep-alive disabled", shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `ensure does nothing while chain is fresh`() {
        every { userData.keepAliveEnabled } returns true
        advanceMinutes(16) // below 20 min stale window (covers the legit ~15 min Doze stretch)

        manager.ensureAlarmChainAlive("test")

        assertNull("must not re-arm before stale window", shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `ensure re-arms alarm when chain is stale`() {
        every { userData.keepAliveEnabled } returns true
        advanceMinutes(21) // past 20 min stale window

        manager.ensureAlarmChainAlive("test")

        assertNotNull("must re-arm alarm when chain is stale", shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `ensure uses extended stale window without exact alarm permission`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        every { userData.keepAliveEnabled } returns true

        advanceMinutes(60) // way past the exact window, below the 2h+5min inexact window
        manager.ensureAlarmChainAlive("test")
        assertNull(
            "must not re-arm within the legit inexact Doze deferral (30min-2h)",
            shadowAlarmManager.nextScheduledAlarm
        )

        advanceMinutes(70) // 130 min total, past 2h + 5 min
        manager.ensureAlarmChainAlive("test")
        assertNotNull("must re-arm past the inexact stale window", shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `scheduleAlarmCheck resets staleness so ensure does not re-arm`() {
        every { userData.keepAliveEnabled } returns true

        manager.scheduleAlarmCheck()
        val originalTrigger = shadowAlarmManager.nextScheduledAlarm!!.triggerAtMs

        // Advancing time may fire-and-remove the due alarm in Robolectric; the invariant
        // under test is only that ensure() arms nothing NEW while the chain is fresh.
        advanceMinutes(16) // below 20 min stale window
        manager.ensureAlarmChainAlive("test")

        assertTrue(
            "ensure must not re-arm a fresh chain",
            shadowAlarmManager.scheduledAlarms.none { it.triggerAtMs > originalTrigger }
        )
    }

    @Test
    fun `ensure re-arms again after healing once and going stale again`() {
        every { userData.keepAliveEnabled } returns true

        advanceMinutes(21)
        manager.ensureAlarmChainAlive("first")
        val firstTrigger = shadowAlarmManager.nextScheduledAlarm!!.triggerAtMs

        advanceMinutes(21) // swallowed again — no ServiceCheckReceiver re-arm happened
        manager.ensureAlarmChainAlive("second")
        val secondTrigger = shadowAlarmManager.nextScheduledAlarm!!.triggerAtMs

        assertEquals("healed alarm must be re-armed relative to the new now", firstTrigger + 21 * 60_000L, secondTrigger)
    }
}
