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
        
        
        InactiveDeviceNotifier.clear()
        unmockkAll()
    }

    




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

    
    @Test
    fun `handshake rejection confirmed as 401 logs out exactly once`() = guardTest { guard ->
        coEvery { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) } throws AuthorizationFailedException(401, "nope")

        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        coVerify(exactly = 1) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
        verify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
    }

    @Test
    fun `a rejection of a credential replaced during the confirm does not log the new login out`() = guardTest { guard ->
        coEvery { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) } answers {
            every { userManager.getUserData() } returns UserData(baseAuth = "Basic bmV3")
            throw AuthorizationFailedException(401, "old login revoked")
        }

        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    
    @Test
    fun `five back-to-back rejections collapse into one confirm`() = guardTest { guard ->
        repeat(5) { stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED) }
        runCurrent()

        coVerify(exactly = 1) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
    }

    
    @Test
    fun `a second confirm waits out the five second minimum interval`() = guardTest { guard ->
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent()
        advanceTimeBy(4_999)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent()
        coVerify(exactly = 1) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }

        advanceTimeBy(2)
        runCurrent()
        coVerify(exactly = 2) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
    }

    
    @Test
    fun `a 2xx confirm is a false alarm and leaves the guard armed`() = guardTest { guard ->
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent()
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }

        advanceTimeBy(5_001)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        coVerify(exactly = 2) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    
    @Test
    fun `a network error is inconclusive and leaves the guard armed`() = guardTest { guard ->
        coEvery { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) } throws PushNetworkException(IOException("offline"))

        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent()
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }

        advanceTimeBy(5_001)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        coVerify(exactly = 2) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    
    @Test
    fun `after a confirmed kick every later signal is dropped`() = guardTest { guard ->
        coEvery { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) } throws AuthorizationFailedException(401, "nope")

        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        guard.onAuthRejected(AuthFailureSource.DEVICE_API)
        guard.onAuthRejected(AuthFailureSource.MESSAGE_SEND)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceTimeBy(60_000)
        advanceUntilIdle()

        coVerify(exactly = 1) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
        verify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
    }

    
    
    @Test
    fun `a reconnect does not discard a signal queued during the cooldown`() = guardTest { guard ->
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        runCurrent() 
        guard.onAuthRejected(AuthFailureSource.DEVICE_API)
        stateFlow.emit(WebSocketConnectionState.CONNECTED)
        runCurrent()
        coVerify(exactly = 1) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }

        advanceTimeBy(10_000)
        advanceUntilIdle()
        coVerify(exactly = 2) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    
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

        coVerify(exactly = 0) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    
    @Test
    fun `451 raises the activation prompt instead of confirming`() = guardTest { guard ->
        InactiveDeviceNotifier.pending.test {
            assertFalse(awaitItem())
            stateFlow.emit(WebSocketConnectionState.INACTIVE_FAILED)
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    
    @Test
    fun `a reconnect clears the activation prompt`() = guardTest { guard ->
        stateFlow.emit(WebSocketConnectionState.INACTIVE_FAILED)
        advanceUntilIdle()
        assertTrue(InactiveDeviceNotifier.pending.value)

        stateFlow.emit(WebSocketConnectionState.CONNECTED)
        advanceUntilIdle()
        assertFalse(InactiveDeviceNotifier.pending.value)
    }

    
    @Test
    fun `no credential means no confirming call`() = guardTest { guard ->
        every { userManager.getUserData() } returns UserData(baseAuth = null)

        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceUntilIdle()

        coVerify(exactly = 0) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
        verify(exactly = 0) { logoutManager.doLogoutWithoutRemoveData() }
    }

    
    @Test
    fun `a sink report confirmed as 403 logs out`() = guardTest { guard ->
        coEvery { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) } throws AuthorizationFailedException(403, "nope")

        guard.onAuthRejected(AuthFailureSource.MESSAGE_SEND)
        advanceUntilIdle()

        coVerify(exactly = 1) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
        verify(exactly = 1) { logoutManager.doLogoutWithoutRemoveData() }
    }

    
    @Test
    fun `a stopped guard consumes nothing`() = guardTest { guard ->
        guard.stop()

        guard.onAuthRejected(AuthFailureSource.DEVICE_API)
        stateFlow.emit(WebSocketConnectionState.AUTHENTICATION_FAILED)
        advanceTimeBy(30_000)
        advanceUntilIdle()

        coVerify(exactly = 0) { deviceRepository.checkDeviceAuthAs("Basic dGVzdA==", true) }
    }
}
