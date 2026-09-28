package com.difft.android.chat.websocket

import app.cash.turbine.test
import com.difft.android.base.session.AuthFailureSource
import com.difft.android.base.session.InactiveDeviceNotifier
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.push.exceptions.PushNetworkException
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import com.difft.android.websocket.internal.websocket.WebSocketConnection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [SessionAuthGuard] — the single owner of the "credential rejected server-side"
 * decision (issue #1230).
 *
 * The guard is driven from a real `MutableSharedFlow(replay = 1, DROP_OLDEST)`, the exact type
 * `WebSocketConnection.webSocketConnectionState` exposes in production. That matters: the flow does
 * NOT conflate equal consecutive values, so every failed handshake re-emits AUTHENTICATION_FAILED
 * and the single-flight + min-interval behaviour tested here is load-bearing, not defensive.
 *
 * The guard is constructed against the test's own `TestScope`, so `delay(MIN_CONFIRM_INTERVAL_MS)`
 * runs on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionAuthGuardTest {

    private lateinit var stateFlow: MutableSharedFlow<WebSocketConnectionState>
    private lateinit var appWebSocketHelper: AppWebSocketHelper
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var logoutManager: LogoutManager
    private lateinit var userManager: UserManager

    @Before
    fun setUp() {
        stateFlow = MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val conn: WebSocketConnection = mockk(relaxed = true)
        every { conn.webSocketConnectionState } returns stateFlow
        appWebSocketHelper = mockk(relaxed = true)
        every { appWebSocketHelper.chatDataWebSocketConnection } returns conn

        deviceRepository = mockk(relaxed = true)
        logoutManager = mockk(relaxed = true)
        userManager = mockk(relaxed = true)
        every { userManager.getUserData() } returns UserData(baseAuth = "Basic dGVzdA==")
    }

    @After
    fun tearDown() {
        // InactiveDeviceNotifier is a process-global object; leaving it raised would leak into
        // any other test in this module.
        InactiveDeviceNotifier.clear()
        unmockkAll()
    }

    /**
     * Runs [body] against a started guard and always stops it afterwards: the guard's socket-state
     * collector never completes on its own, and `runTest` fails the test if a coroutine launched in
     * its scope is still active when the body returns.
     */
    private fun guardTest(body: suspend TestScope.(SessionAuthGuard) -> Unit) = runTest {
        val guard = SessionAuthGuard(
            appWebSocketHelper = appWebSocketHelper,
            deviceRepository = deviceRepository,
            logoutManager = dagger.Lazy { logoutManager },
            userManager = userManager,
            scope = this,
        )
        guard.start()
        try {
            body(guard)
        } finally {
            guard.stop()
        }
    }

    // T1
    @Test
    fun `handshake rejection confirmed as 401 logs out exactly once`() = guardTest { guard ->
        coEvery { deviceRepository.checkDeviceAuth() } throws AuthorizationFailedException(401, "nope")

        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        coVerify(exactly = 1) { deviceRepository.checkDeviceAuth() }
        verify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // T2
    @Test
    fun `five back-to-back rejections collapse into one confirm`() = guardTest { guard ->
        repeat(5) { stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED) }
        runCurrent()

        coVerify(exactly = 1) { deviceRepository.checkDeviceAuth() }
    }

    // T3
    @Test
    fun `a second confirm waits out the five second minimum interval`() = guardTest { guard ->
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent()
        advanceTimeBy(4_999)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent()
        coVerify(exactly = 1) { deviceRepository.checkDeviceAuth() }

        advanceTimeBy(2)
        runCurrent()
        coVerify(exactly = 2) { deviceRepository.checkDeviceAuth() }
    }

    // T4
    @Test
    fun `a 2xx confirm is a false alarm and leaves the guard armed`() = guardTest { guard ->
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent()
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }

        advanceTimeBy(5_001)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        coVerify(exactly = 2) { deviceRepository.checkDeviceAuth() }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // T5
    @Test
    fun `a network error is inconclusive and leaves the guard armed`() = guardTest { guard ->
        coEvery { deviceRepository.checkDeviceAuth() } throws PushNetworkException(IOException("offline"))

        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent()
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }

        advanceTimeBy(5_001)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        coVerify(exactly = 2) { deviceRepository.checkDeviceAuth() }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // T6
    @Test
    fun `after a confirmed kick every later signal is dropped`() = guardTest { guard ->
        coEvery { deviceRepository.checkDeviceAuth() } throws AuthorizationFailedException(401, "nope")

        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        guard.onAuthRejected(AuthFailureSource.DEVICE_API)
        guard.onAuthRejected(AuthFailureSource.MESSAGE_SEND)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceTimeBy(60_000)
        advanceUntilIdle()

        coVerify(exactly = 1) { deviceRepository.checkDeviceAuth() }
        verify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // T7 — a reconnect does NOT discard a queued signal: the socket coming up says nothing about a
    // 401 another Basic-auth channel just saw. The queued signal still confirms after the cooldown.
    @Test
    fun `a reconnect does not discard a signal queued during the cooldown`() = guardTest { guard ->
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent() // first confirm done, consumer now inside delay()
        guard.onAuthRejected(AuthFailureSource.DEVICE_API)
        stateFlow.emit(WebSocketConnectionState.CONNECTED)
        runCurrent()
        coVerify(exactly = 1) { deviceRepository.checkDeviceAuth() }

        advanceTimeBy(10_000)
        advanceUntilIdle()
        coVerify(exactly = 2) { deviceRepository.checkDeviceAuth() }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // T8
    @Test
    fun `connectivity states are not triggers`() = guardTest { guard ->
        listOf(
            WebSocketConnectionState.DISCONNECTED,
            WebSocketConnectionState.FAILED,
            WebSocketConnectionState.UNKNOWN_HOST_FAILED,
            WebSocketConnectionState.CONNECTING,
        ).forEach { stateFlow.emit(it) }
        advanceTimeBy(30_000)
        advanceUntilIdle()

        coVerify(exactly = 0) { deviceRepository.checkDeviceAuth() }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // T9
    @Test
    fun `451 raises the activation prompt instead of confirming`() = guardTest { guard ->
        InactiveDeviceNotifier.pending.test {
            assertFalse(awaitItem())
            stateFlow.emit(WebSocketConnectionState.INACTIVE_FAILED)
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { deviceRepository.checkDeviceAuth() }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // T10
    @Test
    fun `a reconnect clears the activation prompt`() = guardTest { guard ->
        stateFlow.emit(WebSocketConnectionState.INACTIVE_FAILED)
        advanceUntilIdle()
        assertTrue(InactiveDeviceNotifier.pending.value)

        stateFlow.emit(WebSocketConnectionState.CONNECTED)
        advanceUntilIdle()
        assertFalse(InactiveDeviceNotifier.pending.value)
    }

    // T11
    @Test
    fun `no credential means no confirming call`() = guardTest { guard ->
        every { userManager.getUserData() } returns UserData(baseAuth = null)

        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        coVerify(exactly = 0) { deviceRepository.checkDeviceAuth() }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // T12
    @Test
    fun `a sink report confirmed as 403 logs out`() = guardTest { guard ->
        coEvery { deviceRepository.checkDeviceAuth() } throws AuthorizationFailedException(403, "nope")

        guard.onAuthRejected(AuthFailureSource.MESSAGE_SEND)
        advanceUntilIdle()

        coVerify(exactly = 1) { deviceRepository.checkDeviceAuth() }
        verify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
    }

    // T13
    @Test
    fun `a stopped guard consumes nothing`() = guardTest { guard ->
        guard.stop()

        guard.onAuthRejected(AuthFailureSource.DEVICE_API)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceTimeBy(30_000)
        advanceUntilIdle()

        coVerify(exactly = 0) { deviceRepository.checkDeviceAuth() }
    }
}
