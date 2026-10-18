package com.difft.android.call

import com.difft.android.call.session.CallSession
import com.difft.android.call.session.CallSessionHolder
import com.difft.android.call.session.command.CallSessionCommands
import com.difft.android.call.session.command.HangupReason
import com.difft.android.call.state.OnGoingCallStateManager
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Accepting an incoming call during a call ([awaitCurrentCallRelease]): the current call is hung up
 * through the session (`INCOMING_ACCEPT`) and the accept waits until that session has ended
 * ([CallSessionHolder.current] cleared), giving up after 15 s. With no published room id there is
 * nothing to hang up; with no live session there is nothing to wait for. Row T4-27.
 *
 * The holder and the state manager are real. Their writers are owned by the `:call` session, so
 * this module drives the fields it reads through their private backing flows ([backing]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitCurrentCallReleaseTest {

    private val holder = CallSessionHolder(mockk(relaxed = true))
    private val ogsm = OnGoingCallStateManager()
    private val current = backing<CallSession?>(holder, "_current").apply { value = mockk(relaxed = true) }
    private val roomId = backing<String?>(ogsm, "_currentRoomId").apply { value = "r1" }
    private val commands = mockk<CallSessionCommands>(relaxed = true)

    @Test
    fun `T4-27 hangs up once and returns true when the session ends at 3 s`() = runTest {
        launch {
            delay(RELEASE_MS)
            current.value = null
        }

        val released = awaitCurrentCallRelease(holder, ogsm, commands)

        assertEquals(true, released)
        assertEquals(RELEASE_MS, currentTime)
        verify(exactly = 1) { commands.hangup(HangupReason.INCOMING_ACCEPT) }
    }

    @Test
    fun `T4-27 gives up with false at exactly 15 s when the session never ends`() = runTest {
        val released = awaitCurrentCallRelease(holder, ogsm, commands)

        assertEquals(false, released)
        assertEquals(TIMEOUT_MS, currentTime)
        verify(exactly = 1) { commands.hangup(HangupReason.INCOMING_ACCEPT) }
    }

    @Test
    fun `T4-27 with no room id nothing is hung up and the wait still runs to the session's end`() = runTest {
        roomId.value = ""
        launch {
            delay(RELEASE_MS)
            current.value = null
        }

        val released = awaitCurrentCallRelease(holder, ogsm, commands)

        assertEquals(true, released)
        assertEquals(RELEASE_MS, currentTime)
        verify(exactly = 0) { commands.hangup(any()) }
    }

    @Test
    fun `T4-27 with no live session it returns true at once and hangs up nothing`() = runTest {
        current.value = null

        val released = awaitCurrentCallRelease(holder, ogsm, commands)

        assertEquals(true, released)
        assertEquals(0L, currentTime)
        verify(exactly = 0) { commands.hangup(any()) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> backing(owner: Any, field: String): MutableStateFlow<T> =
        owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>

    private companion object {
        const val RELEASE_MS = 3_000L
        const val TIMEOUT_MS = 15_000L
    }
}
