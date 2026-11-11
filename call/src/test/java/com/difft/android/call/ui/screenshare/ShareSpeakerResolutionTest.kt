package com.difft.android.call.ui.screenshare

import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test


class ShareSpeakerResolutionTest {

    private val me = participant<LocalParticipant>("me")
    private val sharer = participant<RemoteParticipant>("bob")
    private val carol = participant<RemoteParticipant>("carol")
    private val all = listOf(me, sharer, carol)

    @Test
    fun `the controller's speaker is shown`() {
        val (shown, source) = resolveShareSpeaker("carol", all, sharer, me)
        assertSame(carol, shown)
        assertEquals(FloatingSpeakerSelectionSource.LAYOUT_SPEAKER, source)
    }

    @Test
    fun `you can be the speaker`() {
        val (shown, source) = resolveShareSpeaker("me", all, sharer, me)
        assertSame(me, shown)
        assertEquals(FloatingSpeakerSelectionSource.LAYOUT_SPEAKER, source)
    }

    @Test
    fun `before the controller names anyone the sharer is shown`() {
        val (shown, source) = resolveShareSpeaker(null, all, sharer, me)
        assertSame(sharer, shown)
        assertEquals(FloatingSpeakerSelectionSource.FALLBACK_SCREEN_SHARER, source)
    }

    @Test
    fun `a speaker no longer in the list gives way to the sharer`() {
        val (shown, source) = resolveShareSpeaker("dave", all, sharer, me)
        assertSame(sharer, shown)
        assertEquals(FloatingSpeakerSelectionSource.FALLBACK_SCREEN_SHARER, source)
    }

    private inline fun <reified T : Participant> participant(id: String): T = mockk(relaxed = true) {
        every { identity } returns Participant.Identity(id)
        every { sid } returns Participant.Sid("sid-$id")
    }
}
