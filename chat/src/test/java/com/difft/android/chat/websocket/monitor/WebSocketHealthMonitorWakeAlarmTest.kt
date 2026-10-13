package com.difft.android.chat.websocket.monitor

import android.app.AlarmManager
import android.content.Context
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.network.UrlManager
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Drives the production [WebSocketHealthMonitor] health-check loop (on the real `appScope`)
 * with a real [KeepAliveWakeAlarm] and asserts the wake-alarm contract from issue #1325:
 * a connected socket in keep-alive mode keeps an elapsed-realtime wake armed, firing it sends
 * a keep-alive and re-arms, a socket that connects later is covered once it connects,
 * FCM mode arms nothing and stopping the monitor disarms.
 *
 * Timing is real (the monitor is bound to the process scope), so assertions poll with a
 * generous deadline instead of a virtual clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WebSocketHealthMonitorWakeAlarmTest {

    private lateinit var shadowAlarmManager: ShadowAlarmManager
    private lateinit var userData: UserData
    private lateinit var connection: WebSocketConnection
    private lateinit var stateFlow: MutableSharedFlow<WebSocketConnectionState>
    private lateinit var monitor: WebSocketHealthMonitor

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        shadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        userData = mockk(relaxed = true)
        val userManager = mockk<UserManager>(relaxed = true)
        every { userManager.getUserData() } returns userData

        // Same shape as production: replay = 1, `.value` reads the replay cache.
        stateFlow = MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        connection = mockk(relaxed = true)
        every { connection.webSocketConnectionState } returns stateFlow
        every { connection.name } returns "[ws][test]"

        monitor = WebSocketHealthMonitor(
            context = context,
            urlManager = mockk<UrlManager>(relaxed = true),
            coordinator = mockk<DomainSpeedTestCoordinator>(relaxed = true),
            keepAliveWakeAlarm = KeepAliveWakeAlarm(context, userManager),
        )
    }

    @After
    fun tearDown() {
        monitor.stopMonitoring(connection)
        unmockkAll()
    }

    @Test
    fun `connected socket in keep-alive mode keeps an elapsed-realtime wake armed`() {
        every { userData.keepAliveEnabled } returns true
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)

        monitor.monitor(connection)

        val armed = awaitAlarms { it.size == 1 }.single()
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, armed.type)
        assertTrue(armed.onAlarmListener != null)
    }

    @Test
    fun `wake alarm firing sends a keep-alive and re-arms the next wake`() {
        every { userData.keepAliveEnabled } returns true
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)
        monitor.monitor(connection)
        val first = awaitAlarms { it.size == 1 }.single()

        first.onAlarmListener!!.onAlarm()

        verify(timeout = DEADLINE_MS) { connection.sendKeepAlive() }
        awaitAlarms { alarms -> alarms.size == 1 && alarms.single().onAlarmListener !== first.onAlarmListener }
    }

    @Test
    fun `socket that connects after monitoring started is covered once connected`() {
        every { userData.keepAliveEnabled } returns true
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTING)
        monitor.monitor(connection)
        Thread.sleep(SETTLE_MS)
        assertTrue("no wake while connecting", shadowAlarmManager.scheduledAlarms.isEmpty())

        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)

        awaitAlarms { it.size == 1 }
    }

    @Test
    fun `FCM mode arms nothing`() {
        every { userData.keepAliveEnabled } returns false
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)

        monitor.monitor(connection)
        Thread.sleep(SETTLE_MS)

        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun `stopMonitoring disarms the pending wake`() {
        every { userData.keepAliveEnabled } returns true
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)
        monitor.monitor(connection)
        awaitAlarms { it.size == 1 }

        monitor.stopMonitoring(connection)

        awaitAlarms { it.isEmpty() }
    }

    private fun awaitAlarms(
        condition: (List<ShadowAlarmManager.ScheduledAlarm>) -> Boolean,
    ): List<ShadowAlarmManager.ScheduledAlarm> {
        val deadline = System.currentTimeMillis() + DEADLINE_MS
        while (System.currentTimeMillis() < deadline) {
            val snapshot = runCatching { shadowAlarmManager.scheduledAlarms.toList() }.getOrNull()
            if (snapshot != null && condition(snapshot)) return snapshot
            Thread.sleep(POLL_MS)
        }
        fail("alarm condition not met within ${DEADLINE_MS}ms: ${shadowAlarmManager.scheduledAlarms}")
        error("unreachable")
    }

    private companion object {
        const val DEADLINE_MS = 5_000L
        const val SETTLE_MS = 500L
        const val POLL_MS = 20L
    }
}
