package com.difft.android.call.handler

import com.difft.android.test.TestDispatcherRule
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * [RtmMessageHandler.toggleMute] must never drop its completion callback: a request that cannot
 * be sent (local participant, participant without an identity) reports `false` exactly once so the
 * caller can surface the failure instead of staying silent.
 */
class RtmMessageHandlerToggleMuteTest {

    // A Main that is never run: send()'s completion hop stays pending instead of reaching a real Main.
    @get:Rule
    val dispatcherRule = TestDispatcherRule(StandardTestDispatcher())

    private val sendJob = SupervisorJob()
    private val room = mockk<Room>(relaxed = true)
    private val subject = RtmMessageHandler(
        room = room,
        scope = CoroutineScope(sendJob + Dispatchers.Unconfined),
        encryptor = { _, _ -> null },
        decryptor = { _, _ -> null },
    )

    /**
     * send() finishes on Dispatchers.IO with a hop to Main. End it here, while this test's Main is still
     * installed: left running, it touches Main after the rule resets it and the failure is reported as
     * an uncaught exception in whichever coroutine test this JVM runs next.
     */
    @After
    fun tearDown() = runBlocking {
        sendJob.cancel()
        // A hop already queued on the test Main completes (as cancelled) only once that Main runs it.
        withTimeout(5_000) {
            while (sendJob.children.any()) {
                dispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
                delay(10)
            }
        }
    }

    @Test
    fun `local participant reports false once and sends nothing`() {
        val results = mutableListOf<Boolean>()
        subject.toggleMute(mockk<LocalParticipant>(relaxed = true)) { results += it }
        assertEquals(listOf(false), results)
    }

    @Test
    fun `remote participant without identity reports false once`() {
        val remote = mockk<RemoteParticipant>(relaxed = true)
        every { remote.identity } returns null
        val results = mutableListOf<Boolean>()
        subject.toggleMute(remote) { results += it }
        assertEquals(listOf(false), results)
    }

    @Test
    fun `remote participant with identity does not complete synchronously`() {
        val remote = mockk<RemoteParticipant>(relaxed = true)
        every { remote.identity } returns Participant.Identity("+12312345678.1")
        val results = mutableListOf<Boolean>()
        // A sendable target goes through send(), which completes asynchronously on Main; what this
        // pins is that the synchronous early-exit branch is NOT taken for it.
        subject.toggleMute(remote) { results += it }
        assertEquals(emptyList<Boolean>(), results)
    }
}
