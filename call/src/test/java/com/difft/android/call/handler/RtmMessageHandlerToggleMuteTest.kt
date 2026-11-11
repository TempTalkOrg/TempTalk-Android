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






class RtmMessageHandlerToggleMuteTest {

    
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

    




    @After
    fun tearDown() = runBlocking {
        sendJob.cancel()
        
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
        
        
        subject.toggleMute(remote) { results += it }
        assertEquals(emptyList<Boolean>(), results)
    }
}
