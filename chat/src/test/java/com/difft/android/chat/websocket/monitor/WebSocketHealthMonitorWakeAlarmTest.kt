package com.difft.android.chat.websocket.monitor

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import com.difft.android.base.monitor.BackgroundPowerSampler
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.network.UrlManager
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import com.difft.android.websocket.api.websocket.LocalDisconnectReason
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Drives the production [WebSocketHealthMonitor] health-check loop (on the real `appScope`)
 * with a real [KeepAliveWakeAlarm] and asserts the wake-alarm contract from issue #1325:
 * a connected socket in keep-alive mode keeps an elapsed-realtime wake armed, firing it sends
 * a keep-alive and re-arms, a socket that connects later is covered once it connects,
 * FCM mode arms nothing and stopping the monitor disarms. Each keep-alive sent in keep-alive
 * mode also arms a response deadline: a response cancels it, a miss force-disconnects.
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
            powerSampler = mockk<BackgroundPowerSampler>(relaxed = true),
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
        awaitAlarms { alarms ->
            alarms.count { !it.isResponseDeadline() } == 1 &&
                alarms.none { it.onAlarmListener === first.onAlarmListener }
        }
    }

    @Test
    fun `keep-alive response cancels its deadline`() {
        every { userData.keepAliveEnabled } returns true
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)
        monitor.monitor(connection)
        val wake = awaitAlarms { it.size == 1 }.single()
        // The alarm is armed just before the loop subscribes to wake events; let it subscribe.
        Thread.sleep(SETTLE_MS)
        wake.onAlarmListener!!.onAlarm()
        awaitAlarms { alarms -> alarms.any { it.isResponseDeadline() } }

        monitor.onKeepAliveResponse()

        awaitAlarms { alarms -> alarms.none { it.isResponseDeadline() } }
        verify(exactly = 0) { connection.disconnectWhenConnected(any()) }
    }

    @Test
    fun `missed response deadline force-disconnects the socket`() {
        every { userData.keepAliveEnabled } returns true
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)
        monitor.monitor(connection)
        // Paused Robolectric clock: move past the monitor's initial response timestamp.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val wake = awaitAlarms { it.size == 1 }.single()
        // The alarm is armed just before the loop subscribes to wake events; let it subscribe.
        Thread.sleep(SETTLE_MS)
        wake.onAlarmListener!!.onAlarm()
        val deadline = awaitAlarms { alarms -> alarms.any { it.isResponseDeadline() } }.single { it.isResponseDeadline() }

        deadline.onAlarmListener!!.onAlarm()

        verify(timeout = DEADLINE_MS) { connection.disconnectWhenConnected(LocalDisconnectReason.HEARTBEAT_LOST) }
    }

    @Test
    fun `deadline of a replaced socket leaves the new socket alone`() {
        every { userData.keepAliveEnabled } returns true
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)
        monitor.monitor(connection)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        val wake = awaitAlarms { it.size == 1 }.single()
        Thread.sleep(SETTLE_MS)
        wake.onAlarmListener!!.onAlarm()
        val deadline = awaitAlarms { alarms -> alarms.any { it.isResponseDeadline() } }.single { it.isResponseDeadline() }

        stateFlow.tryEmit(WebSocketConnectionState.DISCONNECTED)
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)
        Thread.sleep(SETTLE_MS)
        deadline.onAlarmListener!!.onAlarm()
        Thread.sleep(SETTLE_MS)

        verify(exactly = 0) { connection.disconnectWhenConnected(any()) }
    }

    private fun ShadowAlarmManager.ScheduledAlarm.isResponseDeadline(): Boolean =
        triggerAtTime - SystemClock.elapsedRealtime() <= KeepAliveWakeAlarm.RESPONSE_DEADLINE_MS

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
