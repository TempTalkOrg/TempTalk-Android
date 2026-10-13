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

/**
 * [KeepAliveWakeAlarm] contract (issue #1325): in keep-alive mode it arms exactly one
 * elapsed-realtime wake-up listener alarm one cadence out, re-arming replaces it, cancel
 * disarms it, firing runs the callback; in FCM mode it never touches AlarmManager.
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
}
