package com.difft.android.call.handler

import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RtmMessageHandler.toggleMute] must never drop its completion callback: a request that cannot
 * be sent (local participant, participant without an identity) reports `false` exactly once so the
 * caller can surface the failure instead of staying silent.
 */
class RtmMessageHandlerToggleMuteTest {

    private val room = mockk<Room>(relaxed = true)
    private val subject = RtmMessageHandler(
        room = room,
        scope = CoroutineScope(Dispatchers.Unconfined),
        encryptor = { _, _ -> null },
        decryptor = { _, _ -> null },
    )

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
