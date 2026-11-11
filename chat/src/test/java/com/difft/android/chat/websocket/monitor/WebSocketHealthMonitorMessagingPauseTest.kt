package com.difft.android.chat.websocket.monitor

import com.difft.android.base.user.UserData
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WebSocketHealthMonitorMessagingPauseTest {

    private val messaging = TestMessagingPause().open()
    private lateinit var connection: WebSocketConnection
    private lateinit var stateFlow: MutableSharedFlow<WebSocketConnectionState>
    private lateinit var monitor: WebSocketHealthMonitor

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val services = mockk<GlobalHiltEntryPoint>(relaxed = true)
        val userData = mockk<UserData>(relaxed = true)
        every { userData.baseAuth } returns "logged-in"
        every { services.userManager.getUserData() } returns userData
        every { com.difft.android.base.utils.globalServices } returns services
        mockkObject(NetworkConstraint.Companion)
        every { NetworkConstraint.isNetworkAvailable(any()) } returns true

        stateFlow = MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        connection = mockk(relaxed = true)
        every { connection.webSocketConnectionState } returns stateFlow
        every { connection.name } returns "[ws][test]"

        monitor = spyk(
            WebSocketHealthMonitor(
                context = RuntimeEnvironment.getApplication(),
                urlManager = mockk(relaxed = true),
                coordinator = mockk(relaxed = true),
                keepAliveWakeAlarm = mockk(relaxed = true),
                powerSampler = mockk(relaxed = true),
                messagingGate = messaging.gate,
            )
        )
        every { monitor.doConnectInternal(any()) } just Runs
    }

    @After
    fun tearDown() {
        monitor.stopMonitoring(connection)
        unmockkAll()
    }

    @Test
    fun `no connect while paused, connects after resume`() {
        messaging.pauseMessaging()
        stateFlow.tryEmit(WebSocketConnectionState.DISCONNECTED)

        monitor.monitor(connection)
        Thread.sleep(SETTLE_MS)
        verify(exactly = 0) { monitor.doConnectInternal(any()) }

        messaging.resumeMessaging()

        verify(timeout = DEADLINE_MS, exactly = 1) { monitor.doConnectInternal(connection) }
    }

    @Test
    fun `connects right away while messaging is open`() {
        stateFlow.tryEmit(WebSocketConnectionState.DISCONNECTED)

        monitor.monitor(connection)

        verify(timeout = DEADLINE_MS, exactly = 1) { monitor.doConnectInternal(connection) }
    }

    private companion object {
        const val SETTLE_MS = 500L
        const val DEADLINE_MS = 5_000L
    }
}
