package com.difft.android.chat.messages

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.chat.websocket.monitor.WebSocketHealthMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.LocalTime

/**
 * [MessageServiceManager.scheduleAlarmCheck] with [NightAlarmClockWake]: only a Xiaomi device inside
 * the night window arms the keep-alive chain as an alarm clock; every other case keeps the existing
 * exact / inexact alarm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NightAlarmClockWakeTest {

    private lateinit var context: Context
    private lateinit var shadowAlarmManager: ShadowAlarmManager
    private lateinit var userData: UserData
    private lateinit var manager: MessageServiceManager

    private var xiaomi = true
    private var time: LocalTime = LocalTime.of(2, 0)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        registerLauncherActivity()

        NightAlarmClockWake.isEligibleDevice = { xiaomi }
        NightAlarmClockWake.now = { time }

        userData = mockk(relaxed = true)
        val userManager = mockk<UserManager>(relaxed = true)
        every { userManager.getUserData() } returns userData
        val monitor = mockk<WebSocketHealthMonitor>(relaxed = true)
        every { monitor.monitorWakeTicks } returns MutableSharedFlow()
        manager = MessageServiceManager(context, userManager, monitor, mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        NightAlarmClockWake.isEligibleDevice = { false }
        NightAlarmClockWake.now = { LocalTime.now() }
        val listenersField = AppForegroundObserver::class.java.getDeclaredField("listeners")
        listenersField.isAccessible = true
        (listenersField.get(AppForegroundObserver) as MutableSet<*>).clear()
        unmockkAll()
    }

    private fun registerLauncherActivity() {
        val launcher = ComponentName(context, "com.difft.android.Launcher")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(launcher)
        pm.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
    }

    private fun armedDelayMs(): Long {
        val alarm = shadowAlarmManager.peekNextScheduledAlarm()
        assertNotNull(alarm)
        return if (alarm!!.alarmClockInfo != null) {
            alarm.alarmClockInfo!!.triggerTime - System.currentTimeMillis()
        } else {
            alarm.triggerAtMs - SystemClock.elapsedRealtime()
        }
    }

    @Test
    fun `night window spans midnight with an inclusive start and exclusive end`() {
        assertTrue(NightAlarmClockWake.inNightWindow(LocalTime.of(22, 0)))
        assertTrue(NightAlarmClockWake.inNightWindow(LocalTime.of(0, 0)))
        assertTrue(NightAlarmClockWake.inNightWindow(LocalTime.of(9, 59)))
        assertFalse(NightAlarmClockWake.inNightWindow(LocalTime.of(10, 0)))
        assertFalse(NightAlarmClockWake.inNightWindow(LocalTime.of(21, 59)))
    }

    @Test
    fun `a non-Xiaomi device keeps the exact alarm at night`() {
        xiaomi = false

        manager.scheduleAlarmCheck()

        assertNull(shadowAlarmManager.peekNextScheduledAlarm()!!.alarmClockInfo)
        assertEquals(5 * 60_000L, armedDelayMs())
    }

    @Test
    fun `Xiaomi keeps the exact alarm in the daytime`() {
        for (t in listOf(LocalTime.of(14, 0), LocalTime.of(10, 0), LocalTime.of(21, 59))) {
            time = t
            manager.scheduleAlarmCheck()

            assertNull("time=$t", shadowAlarmManager.peekNextScheduledAlarm()!!.alarmClockInfo)
            assertEquals("time=$t", 5 * 60_000L, armedDelayMs())
        }
    }

    @Test
    fun `Xiaomi arms a two-minute alarm clock at night`() {
        for (t in listOf(LocalTime.of(23, 30), LocalTime.of(22, 0), LocalTime.of(9, 59))) {
            time = t
            manager.scheduleAlarmCheck()

            assertNotNull("time=$t", shadowAlarmManager.peekNextScheduledAlarm()!!.alarmClockInfo)
            val delay = armedDelayMs()
            assertTrue("time=$t delay=$delay", delay in (2 * 60_000L - 1_000L)..(2 * 60_000L))
            assertEquals("re-arm replaces the pending alarm", 1, shadowAlarmManager.scheduledAlarms.size)
        }
    }

    @Test
    fun `Xiaomi without exact-alarm access keeps the inexact alarm at night`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        manager.scheduleAlarmCheck()

        assertNull(shadowAlarmManager.peekNextScheduledAlarm()!!.alarmClockInfo)
    }

    @Test
    fun `cancelling keep-alive also removes a pending alarm clock`() {
        manager.scheduleAlarmCheck()
        assertNotNull(shadowAlarmManager.peekNextScheduledAlarm()!!.alarmClockInfo)

        manager.stopService()

        assertNull(shadowAlarmManager.peekNextScheduledAlarm())
    }

    private fun managerWith(alarms: AlarmManager): MessageServiceManager {
        val spied = spyk(context)
        every { spied.getSystemService(Context.ALARM_SERVICE) } returns alarms
        every { spied.packageManager } returns context.packageManager
        val userManager = mockk<UserManager>(relaxed = true)
        every { userManager.getUserData() } returns userData
        val monitor = mockk<WebSocketHealthMonitor>(relaxed = true)
        every { monitor.monitorWakeTicks } returns MutableSharedFlow()
        return MessageServiceManager(spied, userManager, monitor, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `a failing alarm clock falls back to the exact alarm`() {
        val alarms = mockk<AlarmManager>(relaxed = true)
        every { alarms.canScheduleExactAlarms() } returns true
        every { alarms.setAlarmClock(any(), any()) } throws IllegalStateException("vendor")

        managerWith(alarms).scheduleAlarmCheck()

        verify(exactly = 1) { alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, any(), any()) }
        verify(exactly = 0) { alarms.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `a rejected alarm clock and exact alarm fall back to the inexact alarm`() {
        val alarms = mockk<AlarmManager>(relaxed = true)
        every { alarms.canScheduleExactAlarms() } returns true
        every { alarms.setAlarmClock(any(), any()) } throws SecurityException("revoked")
        every { alarms.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")

        managerWith(alarms).scheduleAlarmCheck()

        verify(exactly = 1) { alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, any(), any()) }
    }

    @Test
    fun `a stale chain heals as an alarm clock at night`() {
        every { userData.keepAliveEnabled } returns true
        ShadowSystemClock.advanceBy(Duration.ofMinutes(21))

        manager.ensureAlarmChainAlive("test")

        assertNotNull(shadowAlarmManager.peekNextScheduledAlarm()!!.alarmClockInfo)
    }

    @Test
    fun `the brand gate covers Xiaomi and vivo only`() {
        assertTrue(NightAlarmClockWake.isEligibleBrand(OemBrand.XIAOMI))
        assertTrue(NightAlarmClockWake.isEligibleBrand(OemBrand.VIVO))
        for (brand in listOf(OemBrand.OPPO, OemBrand.HUAWEI, OemBrand.HONOR, OemBrand.SAMSUNG, OemBrand.OTHER)) {
            assertFalse(brand.name, NightAlarmClockWake.isEligibleBrand(brand))
        }
    }
}
