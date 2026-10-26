package com.difft.android.chat.websocket.monitor

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
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
import java.time.Duration

/**
 * [KeepAliveWakeAlarm] contract (issue #1325): in keep-alive mode it arms exactly one
 * elapsed-realtime wake-up listener alarm one cadence out, re-arming replaces it, cancel
 * disarms it, firing runs the callback; in FCM mode it never touches AlarmManager. The response
 * deadline is a second, independent one-shot that only the deadline cancel and [KeepAliveWakeAlarm.cancel]
 * disarm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class KeepAliveWakeAlarmTest {

    private lateinit var shadowAlarmManager: ShadowAlarmManager
    private lateinit var userData: UserData
    private lateinit var alarm: KeepAliveWakeAlarm

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        shadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        userData = mockk(relaxed = true)
        val userManager = mockk<UserManager>(relaxed = true)
        every { userManager.getUserData() } returns userData

        alarm = KeepAliveWakeAlarm(context, userManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `keep-alive mode arms one elapsed-realtime wakeup listener alarm one cadence out`() {
        every { userData.keepAliveEnabled } returns true
        val expectedTrigger = SystemClock.elapsedRealtime() + KeepAliveWakeAlarm.CADENCE_MS

        assertTrue(alarm.arm {})

        val scheduled = shadowAlarmManager.scheduledAlarms.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, scheduled.type)
        assertEquals(expectedTrigger, scheduled.triggerAtTime)
        assertEquals(0L, scheduled.interval)
        assertNull("listener alarm carries no PendingIntent", scheduled.operation)
        assertNotNull(scheduled.onAlarmListener)
    }

    @Test
    fun `FCM mode never arms`() {
        every { userData.keepAliveEnabled } returns false

        assertFalse(alarm.arm {})

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `re-arming replaces the pending wake instead of stacking`() {
        every { userData.keepAliveEnabled } returns true

        assertTrue(alarm.arm {})
        assertTrue(alarm.arm {})

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `cancel disarms the pending wake`() {
        every { userData.keepAliveEnabled } returns true
        assertTrue(alarm.arm {})

        alarm.cancel()

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `firing runs the callback once and a later arm is accepted`() {
        every { userData.keepAliveEnabled } returns true
        var fired = 0
        assertTrue(alarm.arm { fired++ })

        shadowAlarmManager.scheduledAlarms.single().onAlarmListener!!.onAlarm()

        assertEquals(1, fired)
        assertTrue(alarm.arm { fired++ })
        assertEquals(1, fired)
    }

    @Test
    fun `response deadline arms a second wakeup one deadline out`() {
        every { userData.keepAliveEnabled } returns true
        val expectedTrigger = SystemClock.elapsedRealtime() + KeepAliveWakeAlarm.RESPONSE_DEADLINE_MS

        assertTrue(alarm.arm {})
        assertTrue(alarm.armResponseDeadline {})

        val alarms = shadowAlarmManager.scheduledAlarms
        assertEquals(2, alarms.size)
        val deadline = alarms.single { it.triggerAtTime == expectedTrigger }
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline.type)
        assertNotNull(deadline.onAlarmListener)
    }

    @Test
    fun `FCM mode never arms a response deadline`() {
        every { userData.keepAliveEnabled } returns false

        assertFalse(alarm.armResponseDeadline {})

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `cancelling the wake leaves the response deadline armed`() {
        every { userData.keepAliveEnabled } returns true
        assertTrue(alarm.arm {})
        assertTrue(alarm.armResponseDeadline {})

        alarm.cancelWake()

        val remaining = shadowAlarmManager.scheduledAlarms.single()
        assertEquals(SystemClock.elapsedRealtime() + KeepAliveWakeAlarm.RESPONSE_DEADLINE_MS, remaining.triggerAtTime)
    }

    @Test
    fun `response cancel disarms only the deadline and full cancel disarms both`() {
        every { userData.keepAliveEnabled } returns true
        assertTrue(alarm.arm {})
        assertTrue(alarm.armResponseDeadline {})

        alarm.cancelResponseDeadline()
        val remaining = shadowAlarmManager.scheduledAlarms.single()
        assertEquals(SystemClock.elapsedRealtime() + KeepAliveWakeAlarm.CADENCE_MS, remaining.triggerAtTime)

        assertTrue(alarm.armResponseDeadline {})
        alarm.cancel()
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `a pending response deadline is kept rather than pushed back`() {
        every { userData.keepAliveEnabled } returns true
        var fired = 0
        assertTrue(alarm.armResponseDeadline { fired++ })
        val first = shadowAlarmManager.scheduledAlarms.single()

        assertTrue(alarm.armResponseDeadline { fired += 10 })

        assertTrue(shadowAlarmManager.scheduledAlarms.single().onAlarmListener === first.onAlarmListener)
        first.onAlarmListener!!.onAlarm()
        assertEquals(1, fired)
    }

    @Test
    fun `a fired response deadline lets the next one arm`() {
        every { userData.keepAliveEnabled } returns true
        assertTrue(alarm.armResponseDeadline {})
        val first = shadowAlarmManager.scheduledAlarms.single()
        first.onAlarmListener!!.onAlarm()

        assertTrue(alarm.armResponseDeadline {})

        assertTrue(shadowAlarmManager.scheduledAlarms.any { it.onAlarmListener !== first.onAlarmListener })
    }

    @Test
    fun `a pending deadline long past due is replaced`() {
        every { userData.keepAliveEnabled } returns true
        assertTrue(alarm.armResponseDeadline {})
        val swallowed = shadowAlarmManager.scheduledAlarms.single()

        ShadowSystemClock.advanceBy(Duration.ofMillis(KeepAliveWakeAlarm.RESPONSE_DEADLINE_MS * 2 + 1))
        assertTrue(alarm.armResponseDeadline {})

        val remaining = shadowAlarmManager.scheduledAlarms.single()
        assertTrue(remaining.onAlarmListener !== swallowed.onAlarmListener)
    }
}
