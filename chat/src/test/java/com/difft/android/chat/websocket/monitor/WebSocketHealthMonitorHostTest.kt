package com.difft.android.chat.websocket.monitor

import android.content.Context
import android.net.ConnectivityManager
import com.difft.android.base.monitor.BackgroundPowerSampler
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.network.UrlManager
import com.difft.android.network.speedtest.DomainSpeedTestCoordinator
import com.difft.android.websocket.api.websocket.LocalDisconnectReason
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/**
 * How [WebSocketHealthMonitor] feeds the shared host state (#1396): a connect lifts the mark on its
 * host, a heartbeat loss or connect timeout marks it, a proxy-change cancel does not, and a network
 * callback asks the coordinator for a throttled probe.
 *
 * Drives the production monitor on the real `appScope`, so assertions use [verify] with a timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WebSocketHealthMonitorHostTest {

    private val urlManager = mockk<UrlManager>(relaxed = true)
    private val coordinator = mockk<DomainSpeedTestCoordinator>(relaxed = true)
    private lateinit var connection: WebSocketConnection
    private lateinit var stateFlow: MutableSharedFlow<WebSocketConnectionState>
    private lateinit var monitor: WebSocketHealthMonitor
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val userData = mockk<UserData>(relaxed = true)
        every { userData.keepAliveEnabled } returns false
        every { userData.baseAuth } returns "Basic dGVzdA=="
        val userManager = mockk<UserManager>(relaxed = true)
        every { userManager.getUserData() } returns userData

        // The reconnect path reads the login state through the global entry point.
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val globalServices = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { com.difft.android.base.utils.globalServices } returns globalServices
        every { globalServices.userManager } returns userManager

        stateFlow = MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        connection = mockk(relaxed = true)
        every { connection.webSocketConnectionState } returns stateFlow
        every { connection.name } returns "[ws][test]"
        every { connection.closedByServer } returns false

        monitor = WebSocketHealthMonitor(
            context = context,
            urlManager = urlManager,
            coordinator = coordinator,
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
    fun `a connected socket lifts the mark on its host`() {
        stateFlow.tryEmit(WebSocketConnectionState.CONNECTED)

        monitor.monitor(connection)

        verify(timeout = TIMEOUT_MS) { coordinator.onWsConnected() }
        verify(timeout = TIMEOUT_MS) { urlManager.recordChatWebsocketConnected() }
    }

    @Test
    fun `a heartbeat loss marks the host and moves on`() {
        every { connection.localDisconnectReason } returns LocalDisconnectReason.HEARTBEAT_LOST
        stateFlow.tryEmit(WebSocketConnectionState.DISCONNECTED)

        monitor.monitor(connection)

        verify(timeout = TIMEOUT_MS) { urlManager.switchToNextChatWebsocketHost() }
        verify(timeout = TIMEOUT_MS) { coordinator.onWsFailure() }
    }

    @Test
    fun `a connect timeout marks the host and moves on`() {
        every { connection.localDisconnectReason } returns LocalDisconnectReason.CONNECT_TIMEOUT
        stateFlow.tryEmit(WebSocketConnectionState.DISCONNECTED)

        monitor.monitor(connection)

        verify(timeout = TIMEOUT_MS) { urlManager.switchToNextChatWebsocketHost() }
    }

    // T15 — a cancel the app made for a proxy toggle is no host failure.
    @Test
    fun `a proxy change cancel keeps the host`() {
        every { connection.localDisconnectReason } returns LocalDisconnectReason.PROXY_CHANGE
        stateFlow.tryEmit(WebSocketConnectionState.DISCONNECTED)

        monitor.monitor(connection)

        // Negative assertion: give the monitor time to act on the state before checking it did not.
        Thread.sleep(SETTLE_MS)
        verify(exactly = 0) { urlManager.switchToNextChatWebsocketHost() }
        verify(exactly = 0) { coordinator.onWsFailure() }
    }

    @Test
    fun `a transport failure still marks the host`() {
        stateFlow.tryEmit(WebSocketConnectionState.FAILED)

        monitor.monitor(connection)

        verify(timeout = TIMEOUT_MS) { urlManager.switchToNextChatWebsocketHost() }
    }

    @Test
    fun `a network callback asks the coordinator for a throttled probe`() {
        monitor.monitor(connection)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        shadowOf(connectivity).networkCallbacks.forEach { it.onAvailable(ShadowNetwork.newInstance(1)) }

        verify(timeout = TIMEOUT_MS) { coordinator.onNetworkAvailable() }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 500L
    }
}
