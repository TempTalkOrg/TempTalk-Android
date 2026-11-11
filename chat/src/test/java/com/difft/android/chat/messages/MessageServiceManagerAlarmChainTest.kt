package com.difft.android.chat.messages

import android.app.AlarmManager
import android.content.Context
import com.difft.android.base.monitor.FgsStartFailure
import com.difft.android.base.monitor.ForegroundServiceFailureRecorder
import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.base.monitor.StallDayTracker
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.chat.websocket.monitor.WebSocketHealthMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
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







@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MessageServiceManagerAlarmChainTest {

    private lateinit var context: Context
    private lateinit var shadowAlarmManager: ShadowAlarmManager
    private lateinit var userManager: UserManager
    private lateinit var userData: UserData
    private lateinit var manager: MessageServiceManager
    private lateinit var healthCounters: KeepAliveHealthCounters
    private lateinit var fgsFailureRecorder: ForegroundServiceFailureRecorder
    private lateinit var stallDayTracker: StallDayTracker

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

        healthCounters = mockk(relaxed = true)
        fgsFailureRecorder = mockk(relaxed = true)
        stallDayTracker = mockk(relaxed = true)
        manager = MessageServiceManager(context, userManager, monitor, fgsFailureRecorder, healthCounters, stallDayTracker)
    }

    @After
    fun tearDown() {
        
        
        
        val listenersField = AppForegroundObserver::class.java.getDeclaredField("listeners")
        listenersField.isAccessible = true
        (listenersField.get(AppForegroundObserver) as MutableSet<*>).clear()
        unmockkAll()
    }

    @Test
    fun `a start skipped for lack of a notification channel is recorded as a failure`() {
        mockkObject(NotificationChannelSpecs)
        every { NotificationChannelSpecs.preflightForegroundChannel(any(), any()) } returns null

        manager.startService()

        verify(exactly = 1) { fgsFailureRecorder.record(FgsStartFailure.NO_CHANNEL) }
    }

    private fun advanceMinutes(minutes: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(minutes))
    }

    @Test
    fun `enabling keep-alive resumes health collection that a non-sampled start had stopped`() {
        manager.startService()
        verify(exactly = 1) { healthCounters.collecting = true }
    }

    @Test
    fun `disabling keep-alive leaves the collection flag to the next reporter decision`() {
        manager.stopService()
        manager.onFcmAvailable()
        verify(exactly = 0) { healthCounters.collecting = any() }
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
        advanceMinutes(16) 

        manager.ensureAlarmChainAlive("test")

        assertNull("must not re-arm before stale window", shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `ensure re-arms alarm when chain is stale`() {
        every { userData.keepAliveEnabled } returns true
        advanceMinutes(21) 

        manager.ensureAlarmChainAlive("test")

        assertNotNull("must re-arm alarm when chain is stale", shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `a stale chain is recorded as an app stall, a fresh or disabled one is not`() {
        every { userData.keepAliveEnabled } returns false
        advanceMinutes(21)
        manager.ensureAlarmChainAlive("disabled")
        every { userData.keepAliveEnabled } returns true
        manager.ensureAlarmChainAlive("stale") 
        advanceMinutes(16)
        manager.ensureAlarmChainAlive("fresh")

        verify(exactly = 1) { healthCounters.onAppStall(match { it in 21 * 60_000L until 22 * 60_000L }) }
    }

    @Test
    fun `a throwing stall counter does not block the re-arm`() {
        every { userData.keepAliveEnabled } returns true
        every { healthCounters.onAppStall(any()) } throws IllegalStateException("boom")
        advanceMinutes(21)

        manager.ensureAlarmChainAlive("test")

        assertNotNull("telemetry failure must not skip the re-arm", shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `a stall whose re-arm keeps failing is counted once`() {
        val failingAlarms = mockk<AlarmManager>(relaxed = true)
        every { failingAlarms.canScheduleExactAlarms() } returns true
        every { failingAlarms.setExactAndAllowWhileIdle(any(), any(), any()) } throws IllegalStateException("arm")
        every { failingAlarms.setAndAllowWhileIdle(any(), any(), any()) } throws IllegalStateException("arm")
        val failingContext = spyk(context)
        every { failingContext.getSystemService(Context.ALARM_SERVICE) } returns failingAlarms
        val monitor = mockk<WebSocketHealthMonitor>(relaxed = true)
        every { monitor.monitorWakeTicks } returns MutableSharedFlow()
        val failing = MessageServiceManager(failingContext, userManager, monitor, fgsFailureRecorder, healthCounters, stallDayTracker)
        every { userData.keepAliveEnabled } returns true
        advanceMinutes(21)

        failing.ensureAlarmChainAlive("tick-1")
        advanceMinutes(1)
        failing.ensureAlarmChainAlive("tick-2")

        verify(exactly = 1) { healthCounters.onAppStall(any()) }
    }

    @Test
    fun `ensure uses extended stale window without exact alarm permission`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        every { userData.keepAliveEnabled } returns true

        advanceMinutes(60) 
        manager.ensureAlarmChainAlive("test")
        assertNull(
            "must not re-arm within the legit inexact Doze deferral (30min-2h)",
            shadowAlarmManager.nextScheduledAlarm
        )

        advanceMinutes(70) 
        manager.ensureAlarmChainAlive("test")
        assertNotNull("must re-arm past the inexact stale window", shadowAlarmManager.nextScheduledAlarm)
    }

    @Test
    fun `scheduleAlarmCheck resets staleness so ensure does not re-arm`() {
        every { userData.keepAliveEnabled } returns true

        manager.scheduleAlarmCheck()
        val originalTrigger = shadowAlarmManager.nextScheduledAlarm!!.triggerAtMs

        
        advanceMinutes(16) 
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

        advanceMinutes(21) 
        manager.ensureAlarmChainAlive("second")
        val secondTrigger = shadowAlarmManager.nextScheduledAlarm!!.triggerAtMs

        assertEquals("healed alarm must be re-armed relative to the new now", firstTrigger + 21 * 60_000L, secondTrigger)
    }

    @Test
    fun `an arm more than two hours after an exact arm records one stall day`() {
        every { userData.keepAliveEnabled } returns true
        manager.scheduleAlarmCheck()
        advanceMinutes(121)

        manager.scheduleAlarmCheck() 
        manager.ensureAlarmChainAlive("monitor-tick")

        verify(exactly = 1) { stallDayTracker.onStall() }
    }

    @Test
    fun `gaps up to two hours or after an inexact arm are no stall day`() {
        every { userData.keepAliveEnabled } returns true
        manager.scheduleAlarmCheck()
        advanceMinutes(120)
        manager.scheduleAlarmCheck()

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        manager.scheduleAlarmCheck() 
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        advanceMinutes(180)
        manager.scheduleAlarmCheck()

        verify(exactly = 0) { stallDayTracker.onStall() }
    }

    @Test
    fun `the first arm of a process only sets the baseline`() {
        every { userData.keepAliveEnabled } returns true
        advanceMinutes(300)

        manager.scheduleAlarmCheck()

        verify(exactly = 0) { stallDayTracker.onStall() }
    }

    @Test
    fun `time with keep-alive switched off is no stall day`() {
        every { userData.keepAliveEnabled } returns true
        manager.scheduleAlarmCheck()
        every { userData.keepAliveEnabled } returns false
        manager.stopService()
        advanceMinutes(300)
        every { userData.keepAliveEnabled } returns true

        manager.startService()

        verify(exactly = 0) { stallDayTracker.onStall() }
    }

    @Test
    fun `an arm that completes after keep-alive was switched off leaves no baseline`() {
        every { userData.keepAliveEnabled } returns true
        manager.scheduleAlarmCheck()
        every { userData.keepAliveEnabled } returns false
        manager.scheduleAlarmCheck() 
        advanceMinutes(300)
        every { userData.keepAliveEnabled } returns true

        manager.scheduleAlarmCheck()

        verify(exactly = 0) { stallDayTracker.onStall() }
    }

    @Test
    fun `a short stale heal before a long freeze still records the stall day`() {
        every { userData.keepAliveEnabled } returns true
        manager.scheduleAlarmCheck()
        advanceMinutes(21)
        manager.ensureAlarmChainAlive("swallowed") 
        advanceMinutes(150)

        manager.ensureAlarmChainAlive("thaw")

        verify(exactly = 1) { stallDayTracker.onStall() }
    }

    @Test
    fun `a failing arm records nothing and keeps the baseline`() {
        val failingAlarms = mockk<AlarmManager>(relaxed = true)
        every { failingAlarms.canScheduleExactAlarms() } returns true
        every { failingAlarms.setExactAndAllowWhileIdle(any(), any(), any()) } throws IllegalStateException("arm")
        every { failingAlarms.setAndAllowWhileIdle(any(), any(), any()) } throws IllegalStateException("arm")
        val failingContext = spyk(context)
        every { failingContext.getSystemService(Context.ALARM_SERVICE) } returns failingAlarms
        val monitor = mockk<WebSocketHealthMonitor>(relaxed = true)
        every { monitor.monitorWakeTicks } returns MutableSharedFlow()
        val failing = MessageServiceManager(failingContext, userManager, monitor, fgsFailureRecorder, healthCounters, stallDayTracker)
        every { userData.keepAliveEnabled } returns true
        advanceMinutes(300)

        failing.scheduleAlarmCheck()

        verify(exactly = 0) { stallDayTracker.onStall() }
    }

    @Test
    fun `only an off-to-on switch records the keep-alive day`() {
        every { userData.keepAliveEnabled } returns false
        manager.startService()
        every { userData.keepAliveEnabled } returns true
        manager.startService() 

        verify(exactly = 1) { stallDayTracker.onKeepAliveEnabled() }
    }

    @Test
    fun `a throwing stall day tracker does not fail the arm`() {
        every { userData.keepAliveEnabled } returns true
        every { stallDayTracker.onStall() } throws IllegalStateException("boom")
        manager.scheduleAlarmCheck()
        advanceMinutes(121)

        manager.scheduleAlarmCheck()

        assertNotNull(shadowAlarmManager.nextScheduledAlarm)
    }
}
